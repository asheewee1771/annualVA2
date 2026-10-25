package sg.annualva.register;

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
import sg.annualva.access.GrantSource;
import sg.annualva.access.ReturnTrigger;
import sg.annualva.access.Role;
import sg.annualva.access.RoleHolder;
import sg.annualva.access.RoleHolders;
import sg.annualva.access.SuspensionCause;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;

/**
 * The VA POCs' role-holder review (IM8 ac-4, story 80a, ticket 47, ADR 0056): every role holder listed against the System
 * Register, the roles that came back by themselves since the last review (ADR 0055), whether a review is due, and the
 * record of each completed review. A review is due when none has been recorded, when 3 months have passed since the last
 * one (by the business date) and when a Register apply came after it. Its only change is a suspension, or lifting one,
 * which the access module makes ({@link RoleHolders}).
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
     * One role holder, against the Register.
     *
     * @param namedInRegister whether the Register names this person for this role on this System now; null for a role
     *     that does not come from the Register (a VA POC's, an Auditor's)
     * @param own whether it is the caller's own grant, which they may not suspend or restore
     */
    public record Holder(UUID grantId, Role role, String email, @Nullable String displayName, @Nullable UUID systemId,
            @Nullable String systemCode, @Nullable String systemName, GrantSource source, RoleHolder.State state,
            Instant grantedAt, @Nullable Instant expiresAt, @Nullable Instant lastUsedAt, @Nullable Instant lapsedAt,
            @Nullable Instant suspendedAt, @Nullable SuspensionCause suspendedBecause, @Nullable Instant returnedAt,
            @Nullable ReturnTrigger returnTrigger, @Nullable Boolean namedInRegister, boolean signedIn,
            boolean disabledAtIdentityProvider, boolean own) {}

    /**
     * The review as the screen shows it.
     *
     * @param dueBecause why a review is due: {@code never-reviewed}, {@code three-months} or {@code register-applied}
     * @param automaticReturns the holders whose role came back by itself since the last review (all of them before the
     *     first), newest first
     */
    public record View(List<Holder> holders, @Nullable RoleHolderReview lastReview, boolean reviewDue, List<String> dueBecause,
            List<Holder> automaticReturns) {}

    /** The VA POCs first, then by System ID, role and email. */
    private static final Comparator<Holder> ORDER = Comparator
            .comparing((Holder holder) -> holder.systemCode() != null)
            .thenComparing(holder -> holder.systemCode() == null ? "" : holder.systemCode())
            .thenComparing(Holder::role)
            .thenComparing(Holder::email);

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
        this.clock = clock;
        this.roleHolders = roleHolders;
        this.reviews = reviews;
        this.register = register;
        this.calendar = calendar;
        this.user = user;
        this.audit = audit;
        this.actor = actor;
    }

    @Transactional(readOnly = true)
    public View view() {
        List<Holder> holders = holders();
        Optional<RoleHolderReview> last = reviews.latest();
        List<String> due = dueBecause(last);
        List<Holder> returns = holders.stream()
                .filter(holder -> holder.returnedAt() != null
                        && last.map(review -> holder.returnedAt().isAfter(review.reviewedAt())).orElse(true))
                .sorted(Comparator.comparing(Holder::returnedAt).reversed())
                .toList();
        return new View(holders, last.orElse(null), !due.isEmpty(), due, returns);
    }

    /** Records that the caller has completed a review of every role holder listed now. */
    @Transactional
    public RoleHolderReview complete() {
        int count = roleHolders.list().size();
        RoleHolderReview review = reviews.insert(clock.instant(), user.id(), count);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.ROLE_HOLDERS_REVIEWED, AuditOutcome.SUCCESS))
                .with(DetailKey.COUNT, count));
        return review;
    }

    /** Suspends a grant (a step-up action) and answers it as the review lists it. */
    @Transactional
    public Holder suspend(UUID grantId) {
        return against(roleHolders.suspend(grantId));
    }

    /** Lifts a grant's suspension (a step-up action) and answers it as the review lists it. */
    @Transactional
    public Holder restore(UUID grantId) {
        return against(roleHolders.restore(grantId));
    }

    private List<Holder> holders() {
        Map<UUID, ExistingSystem> systems = systems();
        UUID me = user.id();
        Instant now = clock.instant();
        List<Holder> holders = new ArrayList<>();
        for (RoleHolder holder : roleHolders.list()) {
            holders.add(holder(holder, systems, me, now));
        }
        holders.sort(ORDER);
        return holders;
    }

    private Holder against(RoleHolder holder) {
        return holder(holder, systems(), user.id(), clock.instant());
    }

    private Map<UUID, ExistingSystem> systems() {
        return register.systems().stream().collect(Collectors.toMap(ExistingSystem::id, Function.identity()));
    }

    private static Holder holder(RoleHolder holder, Map<UUID, ExistingSystem> systems, UUID me, Instant now) {
        ExistingSystem system = holder.systemId() == null ? null : systems.get(holder.systemId());
        return new Holder(holder.grantId(), holder.role(), holder.email(), holder.displayName(), holder.systemId(),
                system == null ? null : system.code(), system == null ? null : system.name(), holder.source(),
                holder.state(now), holder.grantedAt(), holder.expiresAt(), holder.lastUsedAt(), holder.lapsedAt(),
                holder.suspendedAt(), holder.suspendedBecause(), holder.returnedAt(), holder.returnTrigger(),
                named(holder, system), holder.signedIn(), holder.disabledAtIdentityProvider(),
                me.equals(holder.userId()));
    }

    /** Whether the Register names this person for this role on this System now; null for a role it does not give. */
    private static @Nullable Boolean named(RoleHolder holder, @Nullable ExistingSystem system) {
        String named = switch (holder.role()) {
            case SMO -> system == null ? "" : system.smoEmail();
            case CYBER_MANAGER -> system == null ? "" : system.cyberManagerEmail();
            case VA_POC, AUDITOR -> null;
        };
        if (named == null) {
            return null;
        }
        return system != null && !system.retired() && named.equalsIgnoreCase(holder.email());
    }

    private List<String> dueBecause(Optional<RoleHolderReview> last) {
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
