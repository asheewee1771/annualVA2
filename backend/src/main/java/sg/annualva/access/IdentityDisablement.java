package sg.annualva.access;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.boundary.IdentityProvider.Identity;

/**
 * Whether the identity provider reports a person disabled, and what follows (story 80a, IM8 ac-3: "loses access
 * immediately", ticket 47, ADR 0056). It owns {@code app_user.idp_disabled_at}: a sign-in or step-up the provider allows
 * clears it, and one whose token, or a session check, reports the person disabled sets it. Then every grant of theirs is
 * suspended (audited per grant, by nobody) in one transaction, and every session of theirs ends. Clearing the mark restores
 * nothing: a VA POC restores each grant in the role-holder review.
 */
@Component
class IdentityDisablement {

    static final String ACCOUNT_DISABLED = "account-disabled";

    /** Where the provider's word was seen. */
    enum SeenBy {
        SIGN_IN,
        STEP_UP,
        SESSION_CHECK;

        String code() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    private static final Logger log = LoggerFactory.getLogger(IdentityDisablement.class);

    private final AppUserStore users;
    private final RoleLapse lapse;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final TransactionTemplate transaction;
    private final Clock clock;

    IdentityDisablement(AppUserStore users, RoleLapse lapse, FindByIndexNameSessionRepository<? extends Session> sessions,
            TransactionTemplate transaction, Clock clock) {
        this.users = users;
        this.lapse = lapse;
        this.sessions = sessions;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * A sign-in or step-up callback whose token names this person: they are no longer marked disabled, unless the token
     * says they are, when they lose every grant and session and the callback is refused.
     *
     * @throws SignInRefusedException {@code account-disabled} when the token reports the person disabled
     */
    void admit(Identity identity, UUID userId, SeenBy seenBy) {
        if (!identity.disabled()) {
            users.clearDisabled(userId);
            return;
        }
        disabled(userId, seenBy, null);
        throw new SignInRefusedException(ACCOUNT_DISABLED);
    }

    /**
     * @param userId the person the provider reports disabled
     * @param endedByCaller the session the caller ends itself (the session check's own), left alone here; null for none
     */
    void disabled(UUID userId, SeenBy seenBy, @Nullable String endedByCaller) {
        Instant now = clock.instant();
        Integer suspended = transaction.execute(status -> {
            users.markDisabled(userId, now);
            return lapse.suspendEvery(userId, now);
        });
        // The store is keyed by the user id, so this finds every session of the person.
        sessions.findByPrincipalName(userId.toString()).keySet().stream()
                .filter(id -> !id.equals(endedByCaller))
                .forEach(sessions::deleteById);
        log.atInfo()
                .setMessage("The identity provider reports a person disabled")
                .addKeyValue("event.action", "identity-disabled")
                .addKeyValue("event.category", "iam")
                .addKeyValue("event.reason", seenBy.code())
                .addKeyValue("user.id", userId)
                .addKeyValue("annualva.roles.suspended", suspended)
                .log();
    }
}
