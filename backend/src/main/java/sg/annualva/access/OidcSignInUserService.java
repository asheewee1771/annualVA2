package sg.annualva.access;

import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.IdentityProvider;

/**
 * Where Spring Security's OIDC login meets the sign-in rules: the verified id token's claims are
 * mapped by the identity provider adapter and linked to a user. The userinfo endpoint is never
 * called; everything the application needs is in the id token.
 */
@Component
@Profile("!dev-local")
class OidcSignInUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final IdentityProvider identityProvider;
    private final SignInService signIn;

    OidcSignInUserService(IdentityProvider identityProvider, SignInService signIn) {
        this.identityProvider = identityProvider;
        this.signIn = signIn;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        var identity = identityProvider.identityFrom(request.getIdToken().getClaims());
        return new SignedInOidcUser(signIn.signIn(identity), request.getIdToken());
    }
}
