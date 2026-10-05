package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.stereotype.Service;
import sg.annualva.access.StepUpProperties.Mode;
import sg.annualva.boundary.IdentityProvider.Identity;
import sg.annualva.web.StepUpRequiredException;

/**
 * Step-up (IM8 ac-2, story 77, ADR 0004): a sensitive command asks {@link #require(String)} first,
 * and is refused unless the person signed in again within the freshness window. The fresh sign-in is
 * the same authorization-code round trip as any sign-in, started with {@code prompt=login} and
 * {@code max_age=0}; its callback is recognised by the {@link StepUpIntent} the authorization request
 * carried, checked here (fail closed on a missing or old {@code auth_time}, and on a different person)
 * and then only refreshes {@code auth_time} on the session, never when the session began.
 */
@Service
public class StepUp {

    /** The characters an intent may use: it appears in a URL and in the audit event, so it is a code, not text. */
    static final String INTENT_PATTERN = "[a-z][a-z0-9-]{0,63}";

    static final String MISSING_AUTH_TIME = SignInService.MISSING_AUTH_TIME;
    static final String MISSING_IDENTITY = SignInService.MISSING_IDENTITY;
    static final String MFA_NOT_EVIDENCED = SignInService.MFA_NOT_EVIDENCED;
    static final String STALE_AUTH_TIME = "stale-auth-time";
    static final String IDENTITY_MISMATCH = "identity-mismatch";
    static final String SESSION_ENDED = "session-ended";

    /** Same registration as the sign-in start path ({@code /oauth2/authorization/mcc-sso-login}). */
    private static final String LOGIN_REGISTRATION_ID = "mcc-sso-login";
    private static final Pattern SAFE_RETURN_PATH = Pattern.compile("/[A-Za-z0-9._~/-]{0,199}");
    private static final Pattern INTENT = Pattern.compile(INTENT_PATTERN);

    private final Clock clock;
    private final StepUpProperties properties;
    private final SignInProperties signIn;
    private final AppUserStore users;
    private final AccessAudit audit;
    private final OAuth2AuthorizationRequestResolver authorizationRequests;
    private final HttpSessionOAuth2AuthorizationRequestRepository savedRequests =
            new HttpSessionOAuth2AuthorizationRequestRepository();

    StepUp(Clock clock, StepUpProperties properties, SignInProperties signIn, AppUserStore users, AccessAudit audit,
            OAuth2AuthorizationRequestResolver authorizationRequests) {
        this.clock = clock;
        this.properties = properties;
        this.signIn = signIn;
        this.users = users;
        this.audit = audit;
        this.authorizationRequests = authorizationRequests;
    }

    /**
     * Called by every sensitive command before it does anything. Returns when the caller's last fresh
     * sign-in is within the window; otherwise refuses with a 403 {@code step-up-required} problem.
     *
     * @param intent the fixed code of the sensitive action, such as {@code register-apply}
     */
    public void require(String intent) {
        if (!INTENT.matcher(intent).matches()) {
            throw new IllegalArgumentException("A step-up intent is a lower-case code");
        }
        SessionUser user = currentSessionUser();
        if (user != null && !user.authTime().plus(properties.freshnessWindow()).isBefore(clock.instant())) {
            return;
        }
        // The refusal is audited where the 403 is written (the exception handler), once, after the command's own
        // transaction has ended.
        throw new StepUpRequiredException(intent);
    }

    /** A return path that is only ever a same-origin path of safe characters; anything else is the root. */
    static String safeReturnPath(@Nullable String candidate) {
        boolean safe = candidate != null && SAFE_RETURN_PATH.matcher(candidate).matches() && !candidate.startsWith("//");
        return safe ? candidate : "/";
    }

    /**
     * Starts a step-up for the signed-in person and says where to send the browser. Normally that is
     * the identity provider, asked for a fresh sign-in; when it is configured to ignore {@code prompt}
     * the session ends instead and the person signs in from the start.
     */
    String begin(HttpServletRequest request, HttpServletResponse response, SessionUser user, String intent,
            String returnTo) {
        if (properties.mode() == Mode.FULL_SIGN_IN) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            audit.stepUpFullSignIn(user, intent);
            return request.getContextPath() + "/oauth2/authorization/" + LOGIN_REGISTRATION_ID;
        }
        OAuth2AuthorizationRequest base = authorizationRequests.resolve(request, LOGIN_REGISTRATION_ID);
        if (base == null) {
            throw new IllegalStateException("The sign-in registration is missing");
        }
        var inProgress = new StepUpIntent(intent, returnTo, user.id(), clock.instant());
        OAuth2AuthorizationRequest started = OAuth2AuthorizationRequest.from(base)
                .additionalParameters(parameters -> {
                    parameters.put("prompt", "login");
                    parameters.put("max_age", "0");
                })
                .attributes(attributes -> attributes.put(StepUpIntent.ATTRIBUTE, inProgress))
                .build();
        savedRequests.saveAuthorizationRequest(started, request, response);
        audit.stepUpStarted(user, intent);
        return started.getAuthorizationRequestUri();
    }

    /**
     * The checks on the token a step-up round trip returned. The session's person stays the person; only
     * their {@code auth_time} moves forward.
     *
     * @throws SignInRefusedException with a fixed reason when the round trip does not prove a fresh sign-in
     */
    SteppedUpOidcUser complete(StepUpIntent started, Identity identity, OidcIdToken idToken) {
        SessionUser current = currentSessionUser();
        if (current == null || !current.id().equals(started.userId())) {
            throw new SignInRefusedException(SESSION_ENDED);
        }
        String ssoId = identity.ssoId();
        if (ssoId == null) {
            throw new SignInRefusedException(MISSING_IDENTITY);
        }
        Instant authTime = identity.authTime();
        if (authTime == null) {
            throw new SignInRefusedException(MISSING_AUTH_TIME);
        }
        if (signIn.requireMfaClaim() && !signIn.evidencesMfa(identity)) {
            throw new SignInRefusedException(MFA_NOT_EVIDENCED);
        }
        boolean samePerson = users.findBySsoId(ssoId).filter(known -> known.id().equals(current.id())).isPresent();
        if (!samePerson) {
            throw new SignInRefusedException(IDENTITY_MISMATCH);
        }
        if (authTime.isBefore(started.startedAt().minus(properties.clockSkew()))) {
            throw new SignInRefusedException(STALE_AUTH_TIME);
        }
        return new SteppedUpOidcUser(current.withAuthTime(authTime), idToken, started);
    }

    private static @Nullable SessionUser currentSessionUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof SessionUser user ? user : null;
    }
}
