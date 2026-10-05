package sg.annualva.access;

import java.io.Serial;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The authentication stored in the session after a successful sign-in. Authorities are empty:
 * roles are resolved per request from the role store by a later slice (ADR 0011), never cached
 * here and never taken from token claims.
 */
public final class SessionAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final SessionUser user;

    public SessionAuthentication(SessionUser user) {
        super(List.of());
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public SessionUser getPrincipal() {
        return user;
    }

    @Override
    public Object getCredentials() {
        return "";
    }
}
