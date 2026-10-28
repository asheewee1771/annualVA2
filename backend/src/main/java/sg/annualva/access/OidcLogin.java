package sg.annualva.access;

import sg.annualva.config.NotDevLocal;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.stereotype.Component;

/**
 * Sign-in through the organisation's single sign-on: OIDC login through the MCC client beans, with a step-up callback
 * taken by its own provider ahead of the standard one (ADR 0004). It needs the MCC client registration, so it does not
 * exist under {@code dev-local}, where there is no identity provider to register or discover.
 */
@Component
@NotDevLocal
class OidcLogin implements SignInMethod {

    private final OAuth2AuthorizationRequestResolver authorizationRequestResolver;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    private final OidcSignInUserService userService;
    private final SignInCallbackHandler callbackHandler;
    private final StepUpAuthenticationProvider stepUpProvider;

    OidcLogin(OAuth2AuthorizationRequestResolver authorizationRequestResolver,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient,
            OidcSignInUserService userService, SignInCallbackHandler callbackHandler,
            StepUpAuthenticationProvider stepUpProvider) {
        this.authorizationRequestResolver = authorizationRequestResolver;
        this.tokenResponseClient = tokenResponseClient;
        this.userService = userService;
        this.callbackHandler = callbackHandler;
        this.stepUpProvider = stepUpProvider;
    }

    @Override
    public void apply(HttpSecurity http) throws Exception {
        http
                // A step-up callback is taken by its own provider, ahead of the standard one (ADR 0004).
                .authenticationProvider(stepUpProvider)
                .oauth2Login(login -> login
                        // No generated login page: sign-in starts only at /oauth2/authorization/mcc-sso-login.
                        .loginPage("/")
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequestResolver))
                        .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenResponseClient))
                        .userInfoEndpoint(endpoint -> endpoint.oidcUserService(userService))
                        .successHandler(callbackHandler)
                        .failureHandler(callbackHandler));
    }
}
