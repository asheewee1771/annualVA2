package sg.annualva.access;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of the role store: a person (by email, and by user once they have signed in) holds a role,
 * for one System where the role is per System. A grant counts only while none of lapsed, suspended
 * or revoked is set and it has not expired.
 */
public record RoleGrant(
        UUID id,
        Role role,
        @Nullable UUID userId,
        String email,
        @Nullable UUID systemId,
        GrantSource source,
        Instant grantedAt,
        @Nullable UUID grantedBy,
        @Nullable Instant expiresAt,
        @Nullable Instant lastUsedAt,
        @Nullable Instant lapsedAt,
        @Nullable Instant suspendedAt,
        @Nullable Instant revokedAt)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
