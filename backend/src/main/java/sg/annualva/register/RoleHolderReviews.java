package sg.annualva.register;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.Role;
import sg.annualva.access.RoleGrant;
import sg.annualva.access.RoleHolder;
import sg.annualva.access.RoleHolders;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.web.RejectedRequestException;

/**
 * The VA POCs' role-holder review (IM8 ac-4, story 80a, ticket 47, ADR 0056): every role holder listed against the System
 * Register, the roles that came back by themselves since the last review (ADR 0055), whether a review is due, and the
 * record of each completed review. A review is due when none has been recorded, when 3 months have passed since the last
 * one (by the business date) and when a Register apply came after it. Its only change is a suspension, or lifting one,
 * which the access module makes ({@link RoleHolders}); this class only says where each grant stands against the Register.
 *
 * <p>Reading the list is not audited, as reading the Register list is not (reviewer decisions, 2026-10-06): it names
 * people and Systems, no report or Finding content. Recording a review is.
 */
@Service
public class RoleHolderReviews {

    static final String NEVER_REVIEWED = "never-reviewed";
    static final String THREE_MONTHS = "three-months";
    static final String REGISTER_APPLIED = "register-applied";

    /** How long a review stands before the next one is due (ac-4: at least quarterly). */
    private static final int MONTHS_BETWEEN_REVIEWS = 3;

    /**
     * One role holder, against the Register: the grant and the person (flattened into the same JSON object), and what
     * the review adds.
     *
     * @param namedInRegister whether the Register names this person for this role on this System now; null for a role
     *     that does not come from the Register (a VA POC's, an Auditor's)
     * @param own whether it is the caller's own grant, which they may not suspend or restore
     * @param suspendable whether the caller may suspend it, by the rule the command refuses by
     * @param restorable whether the caller may restore it, likewise
     */
    public record Holder(@JsonUnwrapped RoleHolder holder, @Nullable String systemCode, @Nullable String systemName,
            RoleHolder.State state, @Nullable Boolean namedInRegister, boolean own, boolean suspendable,
            boolean restorable) {

        private RoleGrant grant() {
            return holder.grant();
        }
    }

    /**
     * The review as the screen shows it.
     *
     * @param dueBecause why a review is due: {@code never-reviewed}, {@code three-months} or {@code register-applied}
     * @param automaticReturns the holders whose role came back by itself since the last review (all of them before the
     *     first), newest first
     */
    public record View(List<Holder> holders, @Nullable CompletedReview lastReview, boolean reviewDue,
            List<String> dueBecause, List<Holder> automaticReturns) {}

    /** The VA POCs first, then by System ID, role and email. */
    private static final Comparator<Holder> ORDER = Comparator
            .comparing((Holder holder) -> holder.systemCode() != null)
            .thenComparing(holder -> holder.systemCode() == null ? "" : holder.systemCode())
            .thenComparing(holder -> holder.grant().role())
            .thenComparing(holder -> holder.grant().email());

    private final RoleHolders roleHolders;
    private final RoleHolderReviewStore reviews;
    private final RegisterStore register;
    private final CycleCalendar calendar;
    private final CurrentUser user;
    private final AuditLog audit;
    private final AuditActor actor;
    private final Clock clock;

    RoleHolderReviews(RoleHolders roleHolders, RoleHolderReviewStore reviews, RegisterStore register,
            CycleCalendar calendar, CurrentUser user, AuditLog audit, AuditActor actor, Clock clock) {
        this.roleHolders = roleHolders;
        this.reviews = reviews;
        this.register = register;
        this.calendar = calendar;
        this.user = user;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public View view() {
        Map<UUID, ExistingSystem> systems = register.systems().stream()
                .collect(Collectors.toMap(ExistingSystem::id, Function.identity()));
        UUID caller = user.id();
        Instant now = clock.instant();
        List<Holder> holders = new ArrayList<>();
        for (RoleHolder holder : roleHolders.list()) {
            RoleGrant grant = holder.grant();
            holders.add(against(holder, grant.systemId() == null ? null : systems.get(grant.systemId()), caller, now));
        }
        holders.sort(ORDER);
        Optional<CompletedReview> last = reviews.latest();
        List<String> due = dueBecause(last);
        List<Holder> returns = holders.stream()
                .filter(holder -> returnedSince(holder.grant(), last))
                .sorted(Comparator.comparing((Holder holder) -> holder.grant().returnedAt()).reversed())
                .toList();
        return new View(holders, last.orElse(null), !due.isEmpty(), due, returns);
    }

    /**
     * Records that the caller has completed a review of these role holders (ADR 0056 item 4, decided 2026-10-26): the
     * grants the screen listed, each once; or, when the caller names none, every grant listed now.
     *
     * @param grantIds the grants the caller checked; null to record every grant listed now
     * @throws RejectedRequestException 400 {@code invalid-field} naming {@code grantIds} when one is not a grant the
     *     review lists (unknown, or revoked)
     */
    @Transactional
    public CompletedReview complete(@Nullable List<UUID> grantIds) {
        List<UUID> listed = roleHolders.list().stream().map(holder -> holder.grant().id()).toList();
        List<UUID> checked = grantIds == null ? listed : grantIds.stream().distinct().toList();
        if (!listed.containsAll(checked)) {
            throw RejectedRequestException.invalidField("grantIds",
                    "The field 'grantIds' names a role holder the review does not list.");
        }
        CompletedReview review = reviews.insert(clock.instant(), user.id(), checked);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.ROLE_HOLDERS_REVIEWED, AuditOutcome.SUCCESS))
                .with(DetailKey.COUNT, checked.size()));
        return review;
    }

    /** Suspends a grant (a step-up action, in the access module's transaction) and answers it as the review lists it. */
    public Holder suspend(UUID grantId) {
        return against(roleHolders.suspend(grantId));
    }

    /** Lifts a grant's suspension (a step-up action) and answers it as the review lists it. */
    public Holder restore(UUID grantId) {
        return against(roleHolders.restore(grantId));
    }

    private Holder against(RoleHolder holder) {
        UUID systemId = holder.grant().systemId();
        ExistingSystem system = systemId == null ? null : register.system(systemId).orElse(null);
        return against(holder, system, user.id(), clock.instant());
    }

    private static Holder against(RoleHolder holder, @Nullable ExistingSystem system, UUID caller, Instant now) {
        return new Holder(holder, system == null ? null : system.code(), system == null ? null : system.name(),
                holder.state(now), named(holder.grant(), system), holder.heldBy(caller), holder.suspendableBy(caller),
                holder.restorableBy(caller));
    }

    /** Whether the Register names this person for this role on this System now; null for a role it does not give. */
    private static @Nullable Boolean named(RoleGrant grant, @Nullable ExistingSystem system) {
        if (!Role.PER_SYSTEM.contains(grant.role())) {
            return null;
        }
        if (system == null || system.retired()) {
            return false;
        }
        String named = grant.role() == Role.SMO ? system.smoEmail() : system.cyberManagerEmail();
        return named.equalsIgnoreCase(grant.email());
    }

    /** Whether the grant came back by itself after the last review, or ever when there has been none. */
    private static boolean returnedSince(RoleGrant grant, Optional<CompletedReview> last) {
        Instant returnedAt = grant.returnedAt();
        return returnedAt != null && last.map(review -> returnedAt.isAfter(review.reviewedAt())).orElse(true);
    }

    private List<String> dueBecause(Optional<CompletedReview> last) {
        if (last.isEmpty()) {
            return List.of(NEVER_REVIEWED);
        }
        Instant reviewedAt = last.get().reviewedAt();
        List<String> due = new ArrayList<>();
        LocalDate nextDue = calendar.dateOf(reviewedAt).plusMonths(MONTHS_BETWEEN_REVIEWS);
        if (!calendar.today().isBefore(nextDue)) {
            due.add(THREE_MONTHS);
        }
        if (register.lastApplied().filter(applied -> applied.isAfter(reviewedAt)).isPresent()) {
            due.add(REGISTER_APPLIED);
        }
        return due;
    }
}
