package sg.annualva.access;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;

/**
 * Makes the role store agree with the deployment's configuration at startup: each configured VA POC
 * holds a VA_POC grant with source CONFIG, and a CONFIG grant for anyone no longer configured is
 * revoked, so changing the configuration through its change request is the way a VA POC is replaced.
 * Runs before the server accepts requests. Each grant given or revoked is an audit entry written in the
 * same transaction (no actor: the deployment is the actor); a sync that changes nothing writes none.
 * Emails stay out of the log and the audit entries: they carry the grant id.
 */
@Component
class VaPocSync implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(VaPocSync.class);

    private final VaPocProperties properties;
    private final RoleGrantStore grants;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate transaction;

    VaPocSync(VaPocProperties properties, RoleGrantStore grants, AuditLog audit, Clock clock,
            TransactionTemplate transaction) {
        this.properties = properties;
        this.grants = grants;
        this.audit = audit;
        this.clock = clock;
        this.transaction = transaction;
    }

    @Override
    public void afterSingletonsInstantiated() {
        sync();
    }

    void sync() {
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            Set<String> configured = properties.vaPocs().stream()
                    .map(email -> email.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            List<RoleGrant> held = grants.live(Role.VA_POC, GrantSource.CONFIG);
            Set<String> alreadyHeld = held.stream()
                    .map(grant -> grant.email().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            int revoked = 0;
            for (RoleGrant grant : held) {
                if (!configured.contains(grant.email().toLowerCase(Locale.ROOT))) {
                    grants.revoke(grant.id(), now);
                    record(AuditAction.ROLE_REVOKED, grant);
                    revoked++;
                }
            }
            for (String email : properties.vaPocs()) {
                RoleGrant grant = grants.grant(Role.VA_POC, email, null, GrantSource.CONFIG, null, null, now);
                if (!alreadyHeld.contains(email.toLowerCase(Locale.ROOT))) {
                    record(AuditAction.ROLE_GRANTED, grant);
                }
            }
            log.atInfo()
                    .setMessage("VA POC grants synced from configuration")
                    .addKeyValue("event.action", "va-poc-sync")
                    .addKeyValue("event.category", "iam")
                    .addKeyValue("annualva.va-poc.configured", configured.size())
                    .addKeyValue("annualva.va-poc.revoked", revoked)
                    .log();
        });
    }

    private void record(AuditAction action, RoleGrant grant) {
        audit.record(AuditEvent.of(action, AuditOutcome.SUCCESS)
                .with(DetailKey.ROLE, grant.role().name())
                .with(DetailKey.SOURCE, grant.source().name())
                .with(DetailKey.GRANT_ID, grant.id()));
    }
}
