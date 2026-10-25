package sg.annualva.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives the request its authorities (ADR 0011): a signed-in session is swapped, for this request
 * only, for a {@link RoleAuthentication} built from the role store. Roles are never read from the
 * session or from token claims, and the swapped context is not saved, so a revoked grant takes
 * effect on the very next call. Only {@code /api/**} needs authorities; sign-in, the SPA's assets
 * and the actuator endpoints do not pay for the lookup. Added to the chain by {@link SignInSecurity};
 * not a bean, so the container does not register it a second time.
 */
final class RoleResolutionFilter extends OncePerRequestFilter {

    private final RoleResolver resolver;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    RoleResolutionFilter(RoleResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (contextHolder.getContext().getAuthentication() instanceof SessionAuthentication session) {
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(resolver.resolve(session.getPrincipal()));
            contextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
