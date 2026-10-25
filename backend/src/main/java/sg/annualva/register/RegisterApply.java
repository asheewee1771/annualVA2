package sg.annualva.register;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.RegisterRoles;
import sg.annualva.access.StepUp;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * Applies a confirmed Register change preview (stories 2, 3, 4, 73 and 79): in one transaction the Systems it names are
 * added or updated by System ID, the ones the file left out are retired (never deleted, so their history stays), and the
 * SMO/Tech Lead and Cyber Manager grants are made to follow the Register, so a person the Register no longer names loses
 * access at once. It is the step-up action the permissions matrix names for the Register (ADR 0004, 0017), so it asks for
 * a fresh sign-in before it looks at anything.
 *
 * <p>A preview is a snapshot, not a lock. Two VA POCs can hold previews at once, so the apply compares the Register as it
 * is with the Register each row was classified against, and refuses with 409 when anything differs; applies take one
 * lock, so the second of two concurrent applies sees the first one's changes and is refused. The preview is applied at
 * most once and for an hour. All of it, the audit entries included, commits or rolls back together (ADR 0014). A VA
 * POC's scope is every System, so no ownership check applies (ADR 0013). While two VA Cycles overlap it changes only
 * the newer one: the older one keeps its Systems and Quarters as they were when the newer one opened (ADR 0044).
 *
 * <p>In-flight work (stories 5 to 7, ADR 0049): the apply holds the VA Submissions its rows touch and classifies the rows
 * again ({@link WorkEffects}), refusing when that differs from what the preview showed, and withdraws a retired System's
 * in-flight VA Submissions only when the VA POC confirmed each one.
 */
@Service
public class RegisterApply {

    static final String STEP_UP_INTENT = "register-apply";

    /**
     * What an apply did, as the counts it audits, and how many VA Submissions it withdrew (each audited on its own).
     */
    public record Applied(UUID previewId, Instant appliedAt, int added, int updated, int retired, int unchanged,
            int rolesGranted, int rolesRevoked, int withdrawn) {}

    private final WorkEffects effects;
    private final RegisterStore store;
    private final RegisterRoles roles;
    private final StepUp stepUp;
    private final AuditLog audit;
    private final AuditActor actor;
    private final CurrentUser user;
    private final CycleCalendar calendar;
    private final Clock clock;
    private final RegisterSystems systems;
    private final ApplicationEventPublisher events;
    private final SmoReachability reachability;

    RegisterApply(RegisterStore store, RegisterRoles roles, StepUp stepUp, AuditLog audit, AuditActor actor,
            CurrentUser user, CycleCalendar calendar, Clock clock, RegisterSystems systems,
            ApplicationEventPublisher events, WorkEffects effects, SmoReachability reachability) {
        this.effects = effects;
        this.reachability = reachability;
        this.systems = systems;
        this.store = store;
        this.roles = roles;
        this.stepUp = stepUp;
        this.audit = audit;
        this.actor = actor;
        this.user = user;
        this.calendar = calendar;
        this.clock = clock;
        this.events = events;
    }

    /**
     * @param confirmedWithdrawals the VA Submissions the VA POC confirmed withdrawing, one by one (story 6): exactly those
     *     of the preview's Will withdraw rows
     * @throws ResourceNotFoundException for an id that is not a preview
     * @throws RejectedRequestException 409 when the preview was applied already, has expired, has a blocked row, was made
     *     against a Register that has changed since, or the in-flight work it touches has changed since
     *     ({@code work-changed}); 400 {@code withdrawal-unconfirmed} when the confirmations are not exactly its withdrawals
     */
    @Transactional
    public Applied apply(UUID previewId, Collection<UUID> confirmedWithdrawals) {
        stepUp.require(STEP_UP_INTENT);
        store.lockApply();
        Preview preview = store.preview(previewId).orElseThrow(ResourceNotFoundException::new);
        Instant now = clock.instant();
        // While two Cycles overlap the apply changes only the newer: the older keeps its Systems first (ADR 0044).
        systems.keepOlderCycles(now);
        if (!Preview.WAITING.equals(preview.status())) {
            throw conflict("preview-applied", "This preview has already been applied. Upload the file again to change the Register.");
        }
        if (preview.expired(now)) {
            throw conflict("preview-expired", "This preview has expired. Upload the file again.");
        }
        if (!preview.canApply(now)) {
            throw conflict("preview-blocked", "This preview has a blocked row and cannot be applied. Correct the file and upload it again.");
        }
        List<ExistingSystem> baseline = store.systems();
        if (!stillTrue(preview.rows(), baseline)) {
            throw conflict("register-changed", "The Register has changed since this preview was made. Upload the file again.");
        }
        // The in-flight work as it is now, held until the commit, must be what the preview showed and the VA POC confirmed.
        if (!WorkEffects.sameEffects(preview.rows(), effects.held(preview.rows(), now))) {
            throw conflict("work-changed",
                    "VA Submissions this preview touches have changed since it was made. Upload the file again.");
        }
        if (!preview.withdrawals().equals(Set.copyOf(confirmedWithdrawals))) {
            throw new RejectedRequestException(HttpStatus.BAD_REQUEST, "withdrawal-unconfirmed",
                    "Confirm each VA Submission this apply withdraws, and only those, before applying it.");
        }

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int withdrawn = 0;
        List<UUID> retiredSystems = new ArrayList<>();
        for (PreviewRow row : preview.rows()) {
            switch (row.outcome()) {
                case ADD -> {
                    SystemSnapshot after = row.after();
                    store.insertSystem(row.systemId(), after.name(), after.smoEmail(), after.cyberManagerEmail(),
                            quarter(after), calendar.today());
                    added++;
                }
                case UPDATE -> {
                    SystemSnapshot after = row.after();
                    if (row.before().changesSmoIn(after)) {
                        // Another SMO/Tech Lead: whether the old one's address was refused says nothing of theirs.
                        reachability.newSmo(row.existingSystemId());
                    }
                    store.updateSystem(row.existingSystemId(), after.name(), after.smoEmail(), after.cyberManagerEmail(),
                            quarter(after));
                    announce(row.existingSystemId(), row.before(), after, now);
                    updated++;
                }
                case RETIRE -> {
                    store.retireSystem(row.existingSystemId(), now);
                    retiredSystems.add(row.existingSystemId());
                    withdrawn += effects.withdraw(row, previewId, now);
                }
                case NO_CHANGE -> unchanged++;
                case BLOCKED -> throw new IllegalStateException("A blocked row cannot be applied");
            }
        }
        retiredSystems.forEach(system -> audit.record(actor.attribute(
                AuditEvent.of(AuditAction.REGISTER_SYSTEM_RETIRED, AuditOutcome.SUCCESS).onSystem(system)
                        .with(DetailKey.PREVIEW_ID, previewId))));

        List<RegisterRoles.Named> active = store.systems().stream()
                .filter(system -> !system.retired())
                .map(system -> new RegisterRoles.Named(system.id(), system.smoEmail(), system.cyberManagerEmail()))
                .toList();
        RegisterRoles.Changes changes = roles.follow(active);

        if (!store.markApplied(previewId, now, user.id())) {
            throw conflict("preview-applied", "This preview has already been applied. Upload the file again to change the Register.");
        }
        audit.record(actor.attribute(AuditEvent.of(AuditAction.REGISTER_APPLIED, AuditOutcome.SUCCESS))
                .with(DetailKey.PREVIEW_ID, previewId)
                .with(DetailKey.ADDED, added)
                .with(DetailKey.UPDATED, updated)
                .with(DetailKey.RETIRED, retiredSystems.size())
                .with(DetailKey.UNCHANGED, unchanged)
                .with(DetailKey.ROLES_GRANTED, changes.granted())
                .with(DetailKey.ROLES_REVOKED, changes.revoked()));
        return new Applied(previewId, now, added, updated, retiredSystems.size(), unchanged, changes.granted(),
                changes.revoked(), withdrawn);
    }

    /**
     * Whether the Register is still what every row of the preview was classified against: an ADD names a System ID
     * nobody holds now, every other row names a System that still reads as the row's {@code before}, and no active System
     * has appeared that the preview does not mention (it would have been retired, or listed, had it been there).
     */
    private boolean stillTrue(List<PreviewRow> rows, List<ExistingSystem> baseline) {
        Map<UUID, ExistingSystem> byId = new HashMap<>();
        baseline.forEach(system -> byId.put(system.id(), system));
        Set<UUID> mentioned = new HashSet<>();
        List<String> added = new ArrayList<>();
        for (PreviewRow row : rows) {
            switch (row.outcome()) {
                case ADD -> {
                    if (row.existingSystemId() != null) {
                        return false;
                    }
                    added.add(row.systemId());
                }
                case UPDATE, RETIRE, NO_CHANGE -> {
                    ExistingSystem system = row.existingSystemId() == null ? null : byId.get(row.existingSystemId());
                    if (system == null || !system.snapshot().equals(row.before())) {
                        return false;
                    }
                    mentioned.add(system.id());
                }
                case BLOCKED -> {
                    return false;
                }
            }
        }
        if (!store.codesTaken(added).isEmpty()) {
            return false;
        }
        return baseline.stream().allMatch(system -> system.retired() || mentioned.contains(system.id()));
    }

    /**
     * Publishes what an updated System's SMO/Tech Lead is told about (story 94): a move to another Quarter, and a transfer
     * to another SMO/Tech Lead, each heard by the notifications after the commit (ADR 0043). The submissions hear a move
     * inside the apply's transaction too, and let a Not Started System's Overdue flag follow its new Quarter (ADR 0049).
     */
    private void announce(UUID systemId, SystemSnapshot before, SystemSnapshot after, Instant now) {
        if (before.movesIn(after)) {
            events.publishEvent(new SystemChangeEvent(systemId, SystemChangeEvent.Change.MOVED, null, now));
        }
        if (before.changesSmoIn(after)) {
            events.publishEvent(
                    new SystemChangeEvent(systemId, SystemChangeEvent.Change.TRANSFERRED, before.smoEmail(), now));
        }
    }

    private static int quarter(SystemSnapshot snapshot) {
        return Integer.parseInt(snapshot.quarter().substring(1));
    }

    private static RejectedRequestException conflict(String type, String detail) {
        return new RejectedRequestException(HttpStatus.CONFLICT, type, detail);
    }
}
