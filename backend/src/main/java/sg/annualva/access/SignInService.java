package sg.annualva.access;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.boundary.IdentityProvider.Identity;

/**
 * The sign-in rules: refuse when the token lacks the person's identifier, an {@code auth_time}
 * (fail closed, ADR 0004) or, where required, multi-factor evidence (ac-2); then link the
 * identity to a user by {@code sso_id}, else by a pre-registered email, else create one.
 */
@Service
public class SignInService {

    public static final String MISSING_IDENTITY = "missing-identity";
    public static final String MISSING_AUTH_TIME = "missing-auth-time";
    public static final String MFA_NOT_EVIDENCED = "mfa-not-evidenced";
    public static final String MISSING_EMAIL = "missing-email";
    public static final String EMAIL_NOT_VERIFIED = "email-not-verified";
    public static final String IDENTITY_CONFLICT = "identity-conflict";

    private static final Logger log = LoggerFactory.getLogger(SignInService.class);

    private final AppUserStore users;
    private final SignInProperties properties;
    private final DevIdentityProperties devIdentities;
    private final Clock clock;
    private final TransactionTemplate transaction;

    SignInService(AppUserStore users, SignInProperties properties, DevIdentityProperties devIdentities, Clock clock,
            TransactionTemplate transaction) {
        this.transaction = transaction;
        this.users = users;
        this.properties = properties;
        this.devIdentities = devIdentities;
        this.clock = clock;
        if (!properties.requireVerifiedEmail()) {
            log.atWarn()
                    .setMessage("Verified-email check is disabled in this environment")
                    .addKeyValue("event.action", "sign-in-verified-email-check-disabled")
                    .log();
        }
        if (!properties.requireMfaClaim()) {
            log.atWarn()
                    .setMessage("MFA claim check is disabled in this environment")
                    .addKeyValue("event.action", "sign-in-mfa-check-disabled")
                    .log();
        }
    }

    public SessionUser signIn(Identity identity) {
        String ssoId = identity.ssoId();
        if (ssoId == null) {
            throw new SignInRefusedException(MISSING_IDENTITY);
        }
        Instant authTime = identity.authTime();
        if (authTime == null) {
            throw new SignInRefusedException(MISSING_AUTH_TIME);
        }
        if (properties.requireMfaClaim() && !mfaEvidenced(identity)) {
            throw new SignInRefusedException(MFA_NOT_EVIDENCED);
        }
        // An email the provider sent must be asserted as verified: it links the person to Register roles.
        // An email from the configured dev identities is ours, not the provider's.
        if (properties.requireVerifiedEmail() && identity.email() != null && !identity.emailVerified()) {
            throw new SignInRefusedException(EMAIL_NOT_VERIFIED);
        }
        String email = identity.email() != null ? identity.email() : devIdentities.identities().get(ssoId);
        if (email == null) {
            throw new SignInRefusedException(MISSING_EMAIL);
        }
        return linkOrRetryOnce(ssoId, email, authTime, identity);
    }

    /**
     * A unique key (sso_id or email) can be broken by a concurrent first sign-in of the same person,
     * or by a different person who claims an email another user holds. The failed transaction is
     * rolled back and the lookup repeated once: a concurrent twin is then found and linked; a real
     * conflict breaks the key again and is refused, never a 500.
     */
    private SessionUser linkOrRetryOnce(String ssoId, String email, Instant authTime, Identity identity) {
        try {
            return transaction.execute(status -> link(ssoId, email, authTime, identity));
        } catch (DataIntegrityViolationException first) {
            try {
                return transaction.execute(status -> link(ssoId, email, authTime, identity));
            } catch (DataIntegrityViolationException second) {
                throw new SignInRefusedException(IDENTITY_CONFLICT);
            }
        }
    }

    private SessionUser link(String ssoId, String email, Instant authTime, Identity identity) {
        Instant now = clock.instant();
        AppUser user = users.findBySsoId(ssoId)
                .map(known -> users.refresh(known.id(), email, identity.displayName()))
                .or(() -> users.findUnlinkedByEmail(email)
                        .map(preRegistered -> users.link(preRegistered.id(), ssoId, identity.displayName(), now)))
                .orElseGet(() -> users.create(ssoId, email, identity.displayName(), now));
        return new SessionUser(user.id(), user.email(), user.displayName(), authTime, now);
    }

    private boolean mfaEvidenced(Identity identity) {
        boolean byAmr = identity.amr().stream().anyMatch(properties.mfaAmrValues()::contains);
        boolean byAcr = identity.acr() != null && properties.mfaAcrValues().contains(identity.acr());
        return byAmr || byAcr;
    }
}
