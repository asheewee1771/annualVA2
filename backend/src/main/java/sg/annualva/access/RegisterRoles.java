package sg.annualva.access;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;

/**
 * Makes the role store agree with the System Register (stories 73 and 79, IM8 ac-3): every active System has an SMO/Tech
 * Lead grant and a Cyber Manager grant, with source REGISTER, scoped to that System, and nobody else holds one. A person
 * the Register names who has not signed in yet is granted by email and attached at their first sign-in; a person it no
 * longer names, or a retired System, loses the grant in the same transaction, so their next request is refused (roles
 * are read per request, ADR 0011).
 *
 * <p>It is the only writer of REGISTER grants, and the Register apply is its only caller. Each grant given or revoked is
 * an audit entry about the System, written in the caller's transaction with the role, the source and the grant id; the
 * person's email is never in an entry (lm-19). A grant that already stands is left as it is, except that a lapsed one
 * comes back, because the apply names the person again (story 80a, ADR 0053); a suspended one stays suspended (ticket 47).
 */
@Component
public class RegisterRoles {

    /** The people a System names, as the Register holds them now. */
    public record Named(UUID systemId, String smoEmail, String cyberManagerEmail) {}

    /** How many role grants a reconciliation gave and revoked. */
    public record Changes(int granted, int revoked) {}

    private record Held(Role role, String email, UUID systemId) {}

    private final RoleGrantStore grants;
    private final AuditLog audit;
    private final AuditActor actor;
    private final CurrentUser user;
    private final Clock clock;
    private final RoleLapse lapse;

    RegisterRoles(RoleGrantStore grants, AuditLog audit, AuditActor actor, CurrentUser user, Clock clock,
            RoleLapse lapse) {
        this.lapse = lapse;
        this.grants = grants;
        this.audit = audit;
        this.actor = actor;
        this.user = user;
        this.clock = clock;
    }

    /**
     * @param active every System that is not retired, with its SMO/Tech Lead and Cyber Manager
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Changes follow(Collection<Named> active) {
        Instant now = clock.instant();
        Set<Held> wanted = new HashSet<>();
        for (Named system : active) {
            wanted.add(new Held(Role.SMO, lower(system.smoEmail()), system.systemId()));
            wanted.add(new Held(Role.CYBER_MANAGER, lower(system.cyberManagerEmail()), system.systemId()));
        }
        Set<Held> standing = new HashSet<>();
        int revoked = 0;
        for (Role role : List.of(Role.SMO, Role.CYBER_MANAGER)) {
            for (RoleGrant grant : grants.live(role, GrantSource.REGISTER)) {
                Held held = new Held(role, lower(grant.email()), grant.systemId());
                if (wanted.contains(held)) {
                    standing.add(held);
                    if (grant.lapsedAt() != null) {
                        lapse.returned(grant, ReturnTrigger.REGISTER, now);
                    }
                } else {
                    grants.revoke(grant.id(), now);
                    record(AuditAction.ROLE_REVOKED, grant);
                    revoked++;
                }
            }
        }
        UUID grantedBy = user.id();
        int granted = 0;
        for (Held held : sorted(wanted)) {
            if (!standing.contains(held)) {
                RoleGrant grant = grants.grant(held.role(), held.email(), held.systemId(), GrantSource.REGISTER,
                        grantedBy, null, now);
                record(AuditAction.ROLE_GRANTED, grant);
                granted++;
            }
        }
        return new Changes(granted, revoked);
    }

    private void record(AuditAction action, RoleGrant grant) {
        audit.record(actor.attribute(AuditEvent.of(action, AuditOutcome.SUCCESS))
                .onSystem(grant.systemId())
                .with(DetailKey.ROLE, grant.role().name())
                .with(DetailKey.SOURCE, grant.source().name())
                .with(DetailKey.GRANT_ID, grant.id()));
    }

    /** A fixed order, so two applies of the same Register write their entries in the same order. */
    private static List<Held> sorted(Set<Held> held) {
        List<Held> list = new ArrayList<>(held);
        list.sort((a, b) -> {
            int bySystem = a.systemId().compareTo(b.systemId());
            if (bySystem != 0) {
                return bySystem;
            }
            int byRole = a.role().compareTo(b.role());
            return byRole != 0 ? byRole : a.email().compareTo(b.email());
        });
        return list;
    }

    private static String lower(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
