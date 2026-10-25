package sg.annualva.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * as-11, by the application clock: a signed-in session ends 30 minutes after its last request and
 * 8 hours after sign-in regardless; and ac-3 (ticket 47): it ends when the session check finds the person disabled at
 * the identity provider ({@link IdentityCheck}). Runs before the security context is loaded, so an ended session
 * is simply absent for the rest of the request. The session store's own wall-clock expiry remains a
 * backstop. Added to the filter chain by {@link SignInSecurity}; not a bean, so the container does
 * not register it a second time.
 */
final class SessionLimitsFilter extends OncePerRequestFilter {

    static final String IDLE_TIMEOUT = "idle-timeout";
    static final String ABSOLUTE_LIMIT = "absolute-limit";
    static final String IDENTITY_DISABLED = "identity-provider-disabled";

    private static final Logger log = LoggerFactory.getLogger(SessionLimitsFilter.class);

    private final Clock clock;
    private final SignInProperties properties;
    private final IdentityCheck identityCheck;

    SessionLimitsFilter(Clock clock, SignInProperties properties, IdentityCheck identityCheck) {
        this.clock = clock;
        this.properties = properties;
        this.identityCheck = identityCheck;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SessionAttributes.SIGNED_IN_AT) instanceof Instant signedInAt) {
            enforce(session, signedInAt);
        }
        chain.doFilter(request, response);
    }

    private void enforce(HttpSession session, Instant signedInAt) {
        Instant now = clock.instant();
        Instant lastSeenAt = Objects.requireNonNullElse((Instant) session.getAttribute(SessionAttributes.LAST_SEEN_AT), signedInAt);
        if (now.isAfter(signedInAt.plus(properties.absoluteTimeout()))) {
            end(session, ABSOLUTE_LIMIT);
        } else if (now.isAfter(lastSeenAt.plus(properties.idleTimeout()))) {
            end(session, IDLE_TIMEOUT);
        } else if (!identityCheck.stillEnabled(session, signedInAt, now)) {
            end(session, IDENTITY_DISABLED);
        } else if (!now.equals(lastSeenAt)) {
            session.setAttribute(SessionAttributes.LAST_SEEN_AT, now);
        }
    }

    private static void end(HttpSession session, String reason) {
        log.atInfo()
                .setMessage("Session ended")
                .addKeyValue("event.action", "session-ended")
                .addKeyValue("event.category", "session")
                .addKeyValue("event.reason", reason)
                .addKeyValue("user.id", session.getAttribute(SessionAttributes.USER_ID))
                .log();
        session.invalidate();
    }
}
