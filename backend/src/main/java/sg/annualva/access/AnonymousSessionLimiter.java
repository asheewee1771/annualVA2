package sg.annualva.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * as-4: the anonymous endpoints that start a session ({@code /oauth2/authorization/**} stores the
 * authorization request in it, {@code /api/csrf} the CSRF token) write a row to the session store
 * for every client that arrives without one. This caps how many such sessions one client address
 * may start per minute; a request that carries a live session, or any other path, is not counted,
 * and a refused request creates nothing. Windows follow the application clock. Added to the filter
 * chain by {@link SignInSecurity}; not a bean, so the container does not register it a second time.
 */
final class AnonymousSessionLimiter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_PREFIX = "/oauth2/authorization/";
    private static final String CSRF_PATH = "/api/csrf";
    private static final int PRUNE_ABOVE = 10_000;

    private static final Logger log = LoggerFactory.getLogger(AnonymousSessionLimiter.class);

    private record Window(long minute, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int limitPerMinute;
    private final AccessDeniedHandler tooManyRequests;

    AnonymousSessionLimiter(Clock clock, int limitPerMinute, AccessDeniedHandler tooManyRequests) {
        this.clock = clock;
        this.limitPerMinute = limitPerMinute;
        this.tooManyRequests = tooManyRequests;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (startsSession(request) && !admit(request.getRemoteAddr())) {
            log.atWarn()
                    .setMessage("Anonymous session creation throttled")
                    .addKeyValue("event.action", "anonymous-session-throttled")
                    .addKeyValue("event.category", "authentication")
                    .addKeyValue("event.outcome", "failure")
                    .log();
            tooManyRequests.handle(request, response, new AccessDeniedException("Too many sessions started"));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean startsSession(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean sessionStarting = CSRF_PATH.equals(path) || path.startsWith(AUTHORIZATION_PREFIX);
        return sessionStarting && request.getSession(false) == null;
    }

    private boolean admit(String address) {
        long minute = clock.instant().getEpochSecond() / 60;
        if (windows.size() > PRUNE_ABOVE) {
            windows.values().removeIf(window -> window.minute() != minute);
        }
        Window window = windows.merge(address, new Window(minute, 1),
                (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
        return window.count() <= limitPerMinute;
    }
}
