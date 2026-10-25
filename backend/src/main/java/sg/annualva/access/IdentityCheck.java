package sg.annualva.access;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sg.annualva.access.IdentityDisablement.SeenBy;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.IdentityStatus;

/**
 * The session check (IM8 ac-3, ticket 47, ADR 0056), one of the limits {@link SessionLimitsFilter} enforces: a signed-in
 * session asks the identity provider whether the person has been disabled there, at most once every
 * {@code annualva.sign-in.identity-check-interval} (the sign-in being the first check). A person reported disabled loses
 * every grant and every other session ({@link IdentityDisablement}); the filter ends this one. When the provider cannot
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

    IdentityCheck(IdentityStatus status, AppUserStore users, IdentityDisablement disablement,
            SignInProperties properties) {
        this.status = status;
        this.users = users;
        this.disablement = disablement;
        this.properties = properties;
    }

    /**
     * Whether the session may go on: false when the provider has just reported its person disabled.
     *
     * @param signedInAt when the session began, the first check
     * @param now the instant the filter read from the clock
     */
    boolean stillEnabled(HttpSession session, Instant signedInAt, Instant now) {
        Instant checkedAt = session.getAttribute(SessionAttributes.IDENTITY_CHECKED_AT) instanceof Instant at
                ? at
                : signedInAt;
        if (now.isBefore(checkedAt.plus(properties.identityCheckInterval()))
                || !(session.getAttribute(SessionAttributes.USER_ID) instanceof String userId)) {
            return true;
        }
        session.setAttribute(SessionAttributes.IDENTITY_CHECKED_AT, now);
        UUID person = UUID.fromString(userId);
        Optional<String> ssoId = users.ssoIdOf(person);
        if (ssoId.isEmpty() || !disabledAtProvider(ssoId.get(), person)) {
            return true;
        }
        disablement.disabled(person, SeenBy.SESSION_CHECK, session.getId());
        return false;
    }

    private boolean disabledAtProvider(String ssoId, UUID person) {
        try {
            return status.disabled(ssoId);
        } catch (BoundaryFailureException unavailable) {
            log.atWarn()
                    .setMessage("The identity provider could not say whether a person is disabled")
                    .addKeyValue("event.action", "identity-check-unavailable")
                    .addKeyValue("event.category", "iam")
                    .addKeyValue("user.id", person)
                    .log();
            return false;
        }
    }
}
