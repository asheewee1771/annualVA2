package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * Sign-out: tells the browser to clear cookies and storage (the application is always served
 * over TLS, so the header is sent unconditionally) and logs the ECS event. Spring Security's own
 * handlers end the session.
 */
@Component
class SignOutHandler implements LogoutHandler {

    static final String CLEAR_SITE_DATA = "Clear-Site-Data";
    static final String CLEAR_SITE_DATA_VALUE = "\"cookies\",\"storage\"";

    private static final Logger log = LoggerFactory.getLogger(SignOutHandler.class);

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, @Nullable Authentication authentication) {
        response.setHeader(CLEAR_SITE_DATA, CLEAR_SITE_DATA_VALUE);
        if (authentication != null && authentication.getPrincipal() instanceof SessionUser user) {
            log.atInfo()
                    .setMessage("Signed out")
                    .addKeyValue("event.action", "sign-out")
                    .addKeyValue("event.category", "authentication")
                    .addKeyValue("event.outcome", "success")
                    .addKeyValue("user.id", user.getName())
                    .log();
        }
    }
}
