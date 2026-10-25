package sg.annualva.access;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One grant that has not been revoked, as the role-holder review lists it (ticket 47, IM8 ac-4): who holds which role, for
 * which System, and whether it counts now, with its last use, lapse, suspension and automatic return.
 *
 * @param signedIn whether the person has signed in at least once (a grant made by email waits for the first sign-in)
 * @param disabledAtIdentityProvider whether the identity provider last reported the person disabled
 */
public record RoleHolder(
        UUID grantId,
        Role role,
        @Nullable UUID userId,
        String email,
        @Nullable String displayName,
        @Nullable UUID systemId,
        GrantSource source,
        Instant grantedAt,
        @Nullable Instant expiresAt,
        @Nullable Instant lastUsedAt,
        @Nullable Instant lapsedAt,
        @Nullable Instant suspendedAt,
        @Nullable SuspensionCause suspendedBecause,
        @Nullable Instant returnedAt,
        @Nullable ReturnTrigger returnTrigger,
        boolean signedIn,
        boolean disabledAtIdentityProvider) {

    /** Whether the grant counts: a suspension shows before a lapse, because a suspended grant needs a VA POC first. */
    public enum State {
        ACTIVE,
        LAPSED,
        SUSPENDED,
        EXPIRED
    }

    public State state(Instant now) {
        if (suspendedAt != null) {
            return State.SUSPENDED;
        }
        if (lapsedAt != null) {
            return State.LAPSED;
        }
        return expiresAt != null && !expiresAt.isAfter(now) ? State.EXPIRED : State.ACTIVE;
    }
}
