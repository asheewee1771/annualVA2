package sg.annualva.audit;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the audit log is narrowed by; a null field does not narrow.
 *
 * @param from entries at or after this instant
 * @param to entries before this instant
 * @param within only entries about one of these VA Submissions: the caller's read scope ({@link AuditReadScope})
 */
public record AuditFilter(
        @Nullable UUID actorId,
        @Nullable AuditAction action,
        @Nullable UUID systemId,
        @Nullable UUID submissionId,
        @Nullable Instant from,
        @Nullable Instant to,
        @Nullable Set<UUID> within) {}
