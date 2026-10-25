package sg.annualva.harness;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.IdentityStatus;

/**
 * The identity provider's account status, as the session check asks it (ticket 47): everyone is enabled until a test
 * disables them, and the provider can be made unreachable. Emptied before every test.
 */
public final class FakeIdentityStatus implements IdentityStatus {

    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private volatile boolean unreachable;

    @Override
    public boolean disabled(String ssoId) {
        if (unreachable) {
            throw new BoundaryFailureException("The identity provider could not be reached");
        }
        return disabled.contains(ssoId);
    }

    /** The provider reports this person ({@code afcas.uuid}) disabled from now on. */
    public void disable(String ssoId) {
        disabled.add(ssoId);
    }

    /** Every call fails as a provider that cannot be reached, until {@link #reset}. */
    public void unreachable() {
        unreachable = true;
    }

    public void reset() {
        disabled.clear();
        unreachable = false;
    }
}
