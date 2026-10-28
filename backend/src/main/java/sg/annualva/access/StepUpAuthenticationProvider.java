package sg.annualva.access;

import sg.annualva.config.NotDevLocal;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.IdentityProvider;

/**
 * Takes the callback of an authorization request that began a step-up (it carries a {@link StepUpIntent})
 * and no other: the standard OIDC code exchange and id token checks, then {@link StepUp#complete} in
 * place of the sign-in rules. A failure of any kind becomes a {@link StepUpRefusedException} that knows
 * its step-up, so the callback handlers need no other signal. Registered ahead of the standard provider
 * in the authentication manager; for any other callback it declines.
 */
@Component
@NotDevLocal
class StepUpAuthenticationProvider implements AuthenticationProvider {

    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    private final JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory;
    private final IdentityProvider identityProvider;
    private final StepUp stepUp;

    StepUpAuthenticationProvider(OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient,
            JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory, IdentityProvider identityProvider,
            StepUp stepUp) {
        this.tokenResponseClient = tokenResponseClient;
        this.idTokenDecoderFactory = idTokenDecoderFactory;
        this.identityProvider = identityProvider;
        this.stepUp = stepUp;
    }

    @Override
    public @Nullable Authentication authenticate(Authentication authentication) throws AuthenticationException {
        var login = (OAuth2LoginAuthenticationToken) authentication;
        Object attribute = login.getAuthorizationExchange().getAuthorizationRequest().getAttribute(StepUpIntent.ATTRIBUTE);
        if (!(attribute instanceof StepUpIntent started)) {
            return null;
        }
        var oidc = new OidcAuthorizationCodeAuthenticationProvider(tokenResponseClient,
                request -> stepUp.complete(started, identityProvider.identityFrom(request.getIdToken().getClaims()),
                        request.getIdToken()));
        oidc.setJwtDecoderFactory(idTokenDecoderFactory);
        try {
            return oidc.authenticate(authentication);
        } catch (AuthenticationException failure) {
            String reason = failure instanceof SignInRefusedException refused
                    ? refused.reason()
                    : SignInFailureHandler.PROVIDER_ERROR;
            throw new StepUpRefusedException(started, reason, failure);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2LoginAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
