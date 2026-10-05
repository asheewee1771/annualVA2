package sg.annualva.access;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The OIDC principal that exists only for the duration of the callback request: Spring Security
 * needs an {@link OidcUser} to finish the login, and the success handler then replaces it with a
 * {@link SessionAuthentication} so the id token never reaches the session store.
 */
class SignedInOidcUser implements OidcUser {

    private final SessionUser user;
    private final OidcIdToken idToken;

    SignedInOidcUser(SessionUser user, OidcIdToken idToken) {
        this.user = user;
        this.idToken = idToken;
    }

    SessionUser user() {
        return user;
    }

    @Override
    public String getName() {
        return user.getName();
    }

    @Override
    public Map<String, Object> getClaims() {
        return idToken.getClaims();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return idToken.getClaims();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public @Nullable OidcUserInfo getUserInfo() {
        return null;
    }

    @Override
    public OidcIdToken getIdToken() {
        return idToken;
    }
}
