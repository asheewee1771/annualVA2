package sg.annualva.register;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * POC's scope is every System, so no ownership check applies (ADR 0013).
 */
@Service
public class RegisterApply {

    static final String STEP_UP_INTENT = "register-apply";

    /** What an apply did, as the counts it audits. */
    public record Applied(UUID previewId, Instant appliedAt, int added, int updated, int retired, int unchanged,
            int rolesGranted, int rolesRevoked) {}

    private final RegisterStore store;
    private final RegisterRoles roles;
    private final StepUp stepUp;
    private final AuditLog audit;
    private final AuditActor actor;
    private final CurrentUser user;
    private final CycleCalendar calendar;
    private final Clock clock;

    RegisterApply(RegisterStore store, RegisterRoles roles, StepUp stepUp, AuditLog audit, AuditActor actor,
            CurrentUser user, CycleCalendar calendar, Clock clock) {
        this.store = store;
        this.roles = roles;
        this.stepUp = stepUp;
        this.audit = audit;
        this.actor = actor;
        this.user = user;
        this.calendar = calendar;
        this.clock = clock;
    }

    /**
     * @throws ResourceNotFoundException for an id that is not a preview
     * @throws RejectedRequestException 409 when the preview was applied already, has expired, has a blocked row, or was
     *     made against a Register that has changed since
     */
    @Transactional
    public Applied apply(UUID previewId) {
        stepUp.require(STEP_UP_INTENT);
        store.lockApply();
        Preview preview = store.preview(previewId).orElseThrow(ResourceNotFoundException::new);
        Instant now = clock.instant();
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

        int added = 0;
        int updated = 0;
        int unchanged = 0;
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
                    store.updateSystem(row.existingSystemId(), after.name(), after.smoEmail(), after.cyberManagerEmail(),
                            quarter(after));
                    updated++;
                }
                case RETIRE -> {
                    store.retireSystem(row.existingSystemId(), now);
                    retiredSystems.add(row.existingSystemId());
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
                changes.revoked());
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

    private static int quarter(SystemSnapshot snapshot) {
        return Integer.parseInt(snapshot.quarter().substring(1));
    }

    private static RejectedRequestException conflict(String type, String detail) {
        return new RejectedRequestException(HttpStatus.CONFLICT, type, detail);
    }
}
