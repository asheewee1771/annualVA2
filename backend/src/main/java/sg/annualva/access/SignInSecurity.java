package sg.annualva.access;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

/**
 * The sign-in and session wiring the composition root applies to the one filter chain: OIDC login
 * through the MCC client beans, the lean session principal, sign-out at {@code POST /api/logout},
 * session id rotation at sign-in, one session per person, and the clock-driven idle and absolute
 * limits, and the per-request resolution of roles from the role store. There is no form login: {@code /login} is not an endpoint.
 */
@Component
public class SignInSecurity {

    static final String LOGOUT_PATH = "/api/logout";

    private static final Logger log = LoggerFactory.getLogger(SignInSecurity.class);

    private final OAuth2AuthorizationRequestResolver authorizationRequestResolver;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    private final OidcSignInUserService userService;
    private final SignInCallbackHandler callbackHandler;
    private final StepUpAuthenticationProvider stepUpProvider;
    private final SignOutHandler signOutHandler;
    private final SecurityContextRepository securityContextRepository;
    private final SessionRegistry sessionRegistry;
    private final SessionLimitsFilter sessionLimitsFilter;
    private final RoleResolutionFilter roleResolutionFilter;
    private final Clock clock;
    private final SignInProperties properties;

    SignInSecurity(OAuth2AuthorizationRequestResolver authorizationRequestResolver,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient,
            OidcSignInUserService userService, SignInCallbackHandler callbackHandler,
            StepUpAuthenticationProvider stepUpProvider, SignOutHandler signOutHandler,
            SecurityContextRepository securityContextRepository, SessionRegistry sessionRegistry,
            Clock clock, SignInProperties properties, RoleResolver roleResolver, IdentityCheck identityCheck) {
        this.authorizationRequestResolver = authorizationRequestResolver;
        this.tokenResponseClient = tokenResponseClient;
        this.userService = userService;
        this.callbackHandler = callbackHandler;
        this.stepUpProvider = stepUpProvider;
        this.signOutHandler = signOutHandler;
        this.securityContextRepository = securityContextRepository;
        this.sessionRegistry = sessionRegistry;
        this.sessionLimitsFilter = new SessionLimitsFilter(clock, properties);
        this.roleResolutionFilter = new RoleResolutionFilter(roleResolver, identityCheck);
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * @param unauthenticated the 401 problem writer, also used when an evicted session shows up again
     * @param tooManyRequests the 429 problem writer for throttled anonymous session creation
     */
    public void apply(HttpSecurity http, AuthenticationEntryPoint unauthenticated,
            AccessDeniedHandler tooManyRequests) throws Exception {
        http
                // A step-up callback is taken by its own provider, ahead of the standard one (ADR 0004).
                .authenticationProvider(stepUpProvider)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .oauth2Login(login -> login
                        // No generated login page: sign-in starts only at /oauth2/authorization/mcc-sso-login.
                        .loginPage("/")
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequestResolver))
                        .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenResponseClient))
                        .userInfoEndpoint(endpoint -> endpoint.oidcUserService(userService))
                        .successHandler(callbackHandler)
                        .failureHandler(callbackHandler))
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_PATH)
                        .addLogoutHandler(signOutHandler)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .sessionManagement(sessions -> sessions
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        .sessionConcurrency(concurrency -> concurrency
                                .maximumSessions(1)
                                .sessionRegistry(sessionRegistry)
                                .expiredSessionStrategy(evicted(unauthenticated))))
                .addFilterBefore(sessionLimitsFilter, SecurityContextHolderFilter.class)
                // ADR 0011: authorities come from the role store on every request, after the session is accepted.
                .addFilterBefore(roleResolutionFilter, ExceptionTranslationFilter.class)
                .addFilterBefore(
                        new AnonymousSessionLimiter(clock, properties.anonymousSessionsPerMinute(), tooManyRequests),
                        SessionLimitsFilter.class);
    }

    /** A session ended by a newer sign-in of the same person answers like any missing session. */
    private static SessionInformationExpiredStrategy evicted(AuthenticationEntryPoint unauthenticated) {
        return event -> {
            log.atInfo()
                    .setMessage("Session ended")
                    .addKeyValue("event.action", "session-ended")
                    .addKeyValue("event.category", "session")
                    .addKeyValue("event.reason", "newer-sign-in")
                    .addKeyValue("user.id", event.getSessionInformation().getPrincipal())
                    .log();
            unauthenticated.commence(event.getRequest(), event.getResponse(),
                    new SessionAuthenticationException("Session ended by a newer sign-in"));
        };
    }
}
