package sg.annualva.access;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.IdentityStatus;

/**
 * The session check (IM8 ac-3, ticket 47, ADR 0056): a signed-in session asks the identity provider whether the person has
 * been disabled there, at most once every {@code annualva.sign-in.identity-check-interval} (the sign-in being the first
 * check). A person reported disabled loses every grant and session ({@link IdentityDisablement}). When the provider cannot
 * answer, the session is left as it was and asked again after the interval: failing closed would sign everyone out
 * whenever the provider is down, and the provider's own sign-in still refuses a disabled person.
 */
@Component
class IdentityCheck {

    private static final Logger log = LoggerFactory.getLogger(IdentityCheck.class);

    private final IdentityStatus status;
    private final AppUserStore users;
    private final IdentityDisablement disablement;
    private final SignInProperties properties;
    private final Clock clock;

    IdentityCheck(IdentityStatus status, AppUserStore users, IdentityDisablement disablement,
            SignInProperties properties, Clock clock) {
        this.status = status;
        this.users = users;
        this.disablement = disablement;
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether the person may go on: false when the provider has just reported them disabled and their sessions ended. */
    boolean stillEnabled(HttpSession session, SessionUser user) {
        Instant now = clock.instant();
        Instant checkedAt = session.getAttribute(SessionAttributes.IDENTITY_CHECKED_AT) instanceof Instant at
                ? at
                : user.signedInAt();
        if (now.isBefore(checkedAt.plus(properties.identityCheckInterval()))) {
            return true;
        }
        session.setAttribute(SessionAttributes.IDENTITY_CHECKED_AT, now);
        Optional<String> ssoId = users.ssoIdOf(user.id());
        if (ssoId.isEmpty() || !disabledAtProvider(ssoId.get(), user)) {
            return true;
        }
        disablement.disabled(user.id(), "session-check");
        return false;
    }

    private boolean disabledAtProvider(String ssoId, SessionUser user) {
        try {
            return status.disabled(ssoId);
        } catch (RuntimeException unavailable) {
            log.atWarn()
                    .setMessage("The identity provider could not say whether a person is disabled")
                    .addKeyValue("event.action", "identity-check-unavailable")
                    .addKeyValue("event.category", "iam")
                    .addKeyValue("user.id", user.id())
                    // The type only: what a remote service says may quote the identifier.
                    .addKeyValue("error.type", unavailable.getClass().getSimpleName())
                    .log();
            return false;
        }
    }
}
