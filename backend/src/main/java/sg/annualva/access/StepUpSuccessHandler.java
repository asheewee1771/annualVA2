package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Finishes a step-up: saves the session user with the fresher {@code auth_time}, records the step-up in the audit log and
 * sends the browser back. It touches nothing else: when the session began and its idle clock stay as
 * they were, so a step-up never extends the session.
 */
@Component
class StepUpSuccessHandler implements AuthenticationSuccessHandler {

    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final AccessAudit audit;

    StepUpSuccessHandler(SecurityContextRepository securityContextRepository, AccessAudit audit) {
        this.securityContextRepository = securityContextRepository;
        this.audit = audit;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        var principal = (SteppedUpOidcUser) authentication.getPrincipal();

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(new SessionAuthentication(principal.user()));
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        audit.stepUpCompleted(principal.user(), principal.started().intent());
        response.sendRedirect(request.getContextPath() + principal.started().returnTo() + "?stepup=done");
    }
}
