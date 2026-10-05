package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Finishes a sign-in: swaps the OIDC principal (which carries the id token) for the lean
 * {@link SessionAuthentication}, stamps the session for the idle and absolute limits, records the
 * sign-in in the audit log and sends the browser to the front end. The session id was already rotated and the
 * older session of the same person already ended by the session authentication strategies.
 */
@Component
class SignInSuccessHandler implements AuthenticationSuccessHandler {

    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final AccessAudit audit;

    SignInSuccessHandler(SecurityContextRepository securityContextRepository, AccessAudit audit) {
        this.securityContextRepository = securityContextRepository;
        this.audit = audit;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        SessionUser user = ((SignedInOidcUser) authentication.getPrincipal()).user();

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(new SessionAuthentication(user));
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        HttpSession session = request.getSession();
        session.setAttribute(SessionAttributes.SIGNED_IN_AT, user.signedInAt());
        session.setAttribute(SessionAttributes.LAST_SEEN_AT, user.signedInAt());
        session.setAttribute(SessionAttributes.USER_ID, user.getName());

        audit.signedIn(user);
        response.sendRedirect(request.getContextPath() + "/");
    }
}
