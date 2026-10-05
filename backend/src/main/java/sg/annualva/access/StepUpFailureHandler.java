package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A refused step-up: one audit entry with a fixed reason, and back to the route the person left. The
 * person stays signed in, just not fresh, so the sensitive action is still refused until a step-up
 * succeeds.
 */
@Component
class StepUpFailureHandler {

    private final AccessAudit audit;

    StepUpFailureHandler(AccessAudit audit) {
        this.audit = audit;
    }

    void onRefused(HttpServletRequest request, HttpServletResponse response, StepUpRefusedException refused)
            throws IOException {
        HttpSession session = request.getSession(false);
        audit.stepUpFailed(session != null ? userIdOf(session) : null, refused.started().intent(), refused.reason());
        response.sendRedirect(
                request.getContextPath() + refused.started().returnTo() + "?stepup=refused&reason=" + refused.reason());
    }

    /** The session names its person by user id, written at sign-in; anything else is no person. */
    private static @Nullable UUID userIdOf(HttpSession session) {
        try {
            return session.getAttribute(SessionAttributes.USER_ID) instanceof String id ? UUID.fromString(id) : null;
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
