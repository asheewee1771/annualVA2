package sg.annualva.adapters.local;

import sg.annualva.config.DevLocal;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.IdentityStatus;

/** The identity provider's account status of the {@code dev-local} profile: there is no provider, so nobody is disabled. */
@Component
@DevLocal
class LocalIdentityStatus implements IdentityStatus {

    @Override
    public boolean disabled(String ssoId) {
        return false;
    }
}
