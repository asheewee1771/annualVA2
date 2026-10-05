package sg.annualva.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One stored audit entry as the audit log API returns it. It carries ids and fixed codes only: a Finding is
 * referred to by id and no vulnerability detail is ever part of an entry.
 *
 * @param actorRole the roles the actor held at the time, highest privilege first and comma-separated
 * @param correlationId ties the entry to the application log lines of the same request
 * @param detail allow-listed keys only ({@link DetailKey})
 */
public record AuditEntry(
        UUID id,
        Instant occurredAt,
        @Nullable UUID actorId,
        @Nullable String actorRole,
        AuditAction action,
        AuditOutcome outcome,
        @Nullable UUID systemId,
        @Nullable UUID submissionId,
        @Nullable UUID findingId,
        String correlationId,
        Map<String, Object> detail) {}
