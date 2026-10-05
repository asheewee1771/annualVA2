package sg.annualva.audit;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One thing to be recorded. Build it with {@link #of} and the {@code by}, {@code on} and {@code with} methods; each
 * returns a changed copy.
 *
 * @param actorId the person who acted, or null for an anonymous request or the system itself
 * @param actorRole the roles the actor held, highest privilege first and comma-separated, or null
 */
public record AuditEvent(
        AuditAction action,
        AuditOutcome outcome,
        @Nullable UUID actorId,
        @Nullable String actorRole,
        @Nullable UUID systemId,
        @Nullable UUID submissionId,
        @Nullable UUID findingId,
        AuditDetail detail) {

    public static AuditEvent of(AuditAction action, AuditOutcome outcome) {
        return new AuditEvent(action, outcome, null, null, null, null, null, AuditDetail.none());
    }

    public AuditEvent by(@Nullable UUID actor, @Nullable String roles) {
        return new AuditEvent(action, outcome, actor, roles, systemId, submissionId, findingId, detail);
    }

    public AuditEvent onSystem(UUID system) {
        return new AuditEvent(action, outcome, actorId, actorRole, system, submissionId, findingId, detail);
    }

    public AuditEvent onSubmission(UUID submission) {
        return new AuditEvent(action, outcome, actorId, actorRole, systemId, submission, findingId, detail);
    }

    public AuditEvent onFinding(UUID finding) {
        return new AuditEvent(action, outcome, actorId, actorRole, systemId, submissionId, finding, detail);
    }

    public AuditEvent with(DetailKey key, String code) {
        return withDetail(detail.with(key, code));
    }

    public AuditEvent with(DetailKey key, UUID id) {
        return withDetail(detail.with(key, id));
    }

    public AuditEvent with(DetailKey key, long number) {
        return withDetail(detail.with(key, number));
    }

    private AuditEvent withDetail(AuditDetail changed) {
        return new AuditEvent(action, outcome, actorId, actorRole, systemId, submissionId, findingId, changed);
    }
}
