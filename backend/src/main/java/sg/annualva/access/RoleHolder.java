package sg.annualva.access;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One grant that has not been revoked, as the role-holder review lists it (ticket 47, IM8 ac-4): the grant, and what the
 * person's user record says. It also answers what the review may do with it, by the same rules its commands refuse by.
 *
 * @param signedIn whether the person has signed in at least once (a grant made by email waits for the first sign-in)
 * @param disabledAtIdentityProvider whether the identity provider last reported the person disabled
 */
public record RoleHolder(
        @JsonUnwrapped RoleGrant grant,
        @Nullable String displayName,
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
        if (grant.suspendedAt() != null) {
            return State.SUSPENDED;
        }
        if (grant.lapsedAt() != null) {
            return State.LAPSED;
        }
        return grant.expiresAt() != null && !grant.expiresAt().isAfter(now) ? State.EXPIRED : State.ACTIVE;
    }

    /** A review may suspend it: a role held for a System, not suspended yet, not the caller's own. */
    public boolean suspendableBy(UUID caller) {
        return !grant.heldBy(caller) && Role.PER_SYSTEM.contains(grant.role()) && grant.suspendedAt() == null;
    }

    /** A review may restore it: suspended, the person not disabled at the identity provider, not the caller's own. */
    public boolean restorableBy(UUID caller) {
        return !grant.heldBy(caller) && grant.suspendedAt() != null && !disabledAtIdentityProvider;
    }

    public boolean heldBy(UUID caller) {
        return grant.heldBy(caller);
    }
}
