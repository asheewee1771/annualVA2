package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * Sign-out: tells the browser to clear cookies and storage (the application is always served
 * over TLS, so the header is sent unconditionally) and records the sign-out in the audit log. Spring Security's own
 * handlers end the session.
 */
@Component
class SignOutHandler implements LogoutHandler {

    static final String CLEAR_SITE_DATA = "Clear-Site-Data";
    static final String CLEAR_SITE_DATA_VALUE = "\"cookies\",\"storage\"";

    private final AccessAudit audit;

    SignOutHandler(AccessAudit audit) {
        this.audit = audit;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, @Nullable Authentication authentication) {
        response.setHeader(CLEAR_SITE_DATA, CLEAR_SITE_DATA_VALUE);
        if (authentication != null && authentication.getPrincipal() instanceof SessionUser user) {
            audit.signedOut(user);
        }
    }
}
