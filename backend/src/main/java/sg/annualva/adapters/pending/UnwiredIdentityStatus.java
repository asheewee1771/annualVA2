package sg.annualva.adapters.pending;

import org.springframework.stereotype.Component;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.IdentityStatus;

/**
 * The identity provider's account status until the production adapter exists (ticket 51 builds MCC SSO's service client,
 * once the H4 onboarding says how MCC SSO reports a disabled account). Every call fails as a provider that cannot be
 * reached, so the session check leaves every session as it was and says so in the log; a sign-in whose token reports the
 * person disabled is still refused. The Seam 1 harness replaces it with a fake.
 */
@Component
class UnwiredIdentityStatus implements IdentityStatus {

    @Override
    public boolean disabled(String ssoId) {
        throw new BoundaryFailureException("The identity provider's account status is not wired");
    }
}
