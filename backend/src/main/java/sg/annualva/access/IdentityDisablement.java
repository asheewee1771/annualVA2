package sg.annualva.access;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What happens when the identity provider reports a person disabled (story 80a, IM8 ac-3: "loses access immediately",
 * ticket 47, ADR 0056), whether the sign-in or step-up callback saw it in the token or the session check asked: the
 * person is marked disabled, every grant of theirs is suspended (audited per grant, by nobody) in one transaction, and
 * then every session of theirs ends. A later sign-in the provider allows clears the mark but restores nothing: a VA POC
 * restores each grant in the role-holder review.
 */
@Component
class IdentityDisablement {

    static final String ACCOUNT_DISABLED = "account-disabled";

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
     * @param userId the person the provider reports disabled
     * @param seenBy where it was seen: {@code sign-in}, {@code step-up} or {@code session-check}
     */
    void disabled(UUID userId, String seenBy) {
        Instant now = clock.instant();
        Integer suspended = transaction.execute(status -> {
            users.disabled(userId, now);
            return lapse.suspendEvery(userId, now);
        });
        // Every session of the person, the one carrying this request included: the store is keyed by the user id.
        sessions.findByPrincipalName(userId.toString()).keySet().forEach(sessions::deleteById);
        log.atInfo()
                .setMessage("The identity provider reports a person disabled")
                .addKeyValue("event.action", "identity-disabled")
                .addKeyValue("event.category", "iam")
                .addKeyValue("event.reason", seenBy)
                .addKeyValue("user.id", userId)
                .addKeyValue("annualva.roles.suspended", suspended)
                .log();
    }
}
