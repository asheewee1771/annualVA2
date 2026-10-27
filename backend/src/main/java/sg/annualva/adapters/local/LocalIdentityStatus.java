package sg.annualva.adapters.local;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.IdentityStatus;

/** The identity provider's account status of the {@code dev-local} profile: there is no provider, so nobody is disabled. */
@Component
@Profile("dev-local")
class LocalIdentityStatus implements IdentityStatus {

    @Override
    public boolean disabled(String ssoId) {
        return false;
    }
}
