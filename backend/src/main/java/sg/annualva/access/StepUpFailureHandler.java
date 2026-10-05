package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * A refused step-up: one ECS event with a fixed reason, and back to the route the person left. The
 * person stays signed in, just not fresh, so the sensitive action is still refused until a step-up
 * succeeds.
 */
@Component
class StepUpFailureHandler {

    private final StepUp stepUp;

    StepUpFailureHandler(StepUp stepUp) {
        this.stepUp = stepUp;
    }

    void onRefused(HttpServletRequest request, HttpServletResponse response, StepUpRefusedException refused)
            throws IOException {
        HttpSession session = request.getSession(false);
        stepUp.recordRefused(session != null ? session.getAttribute(SessionAttributes.USER_ID) : null,
                refused.started(), refused.reason());
        response.sendRedirect(
                request.getContextPath() + refused.started().returnTo() + "?stepup=refused&reason=" + refused.reason());
    }
}
