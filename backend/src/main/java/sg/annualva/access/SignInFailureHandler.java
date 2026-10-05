package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * A refused sign-in: no session, one ECS event with a fixed reason code, and a redirect to the
 * front end carrying that code. Nothing from the token or the exception reaches the browser.
 */
@Component
class SignInFailureHandler implements AuthenticationFailureHandler {

    static final String PROVIDER_ERROR = "provider-error";

    /** Error codes worth logging verbatim (RFC 6749 and OIDC core, plus Spring Security's id token checks). */
    private static final Set<String> KNOWN_ERROR_CODES = Set.of(
            "invalid_request", "unauthorized_client", "access_denied", "unsupported_response_type", "invalid_scope",
            "server_error", "temporarily_unavailable", "invalid_client", "invalid_grant", "invalid_token_response",
            "invalid_id_token", "invalid_nonce", "invalid_state_parameter", "authorization_request_not_found",
            "client_registration_not_found", "invalid_redirect_uri_parameter", "invalid_user_info_response");

    private static final Logger log = LoggerFactory.getLogger(SignInFailureHandler.class);

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String reason = exception instanceof SignInRefusedException refused ? refused.reason() : PROVIDER_ERROR;
        String errorCode = exception instanceof OAuth2AuthenticationException oauth
                        && KNOWN_ERROR_CODES.contains(oauth.getError().getErrorCode())
                ? oauth.getError().getErrorCode()
                : "other";
        log.atWarn()
                .setMessage("Sign-in refused")
                .addKeyValue("event.action", "sign-in-refused")
                .addKeyValue("event.category", "authentication")
                .addKeyValue("event.outcome", "failure")
                .addKeyValue("event.reason", reason)
                .addKeyValue("error.code", errorCode)
                .log();
        response.sendRedirect(request.getContextPath() + "/?signin=refused&reason=" + reason);
    }
}
