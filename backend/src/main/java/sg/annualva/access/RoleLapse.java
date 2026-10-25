package sg.annualva.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;

/**
 * The role lapse and its automatic return (story 80a, IM8 ac-3, ADR 0054). A grant unused for 90 days lapses: it stops
 * counting, so the person's next request is refused (roles are read per request, ADR 0011), but the person's sign-in is
 * left alone. An SMO/Tech Lead or Cyber Manager grant comes back by itself when a Register apply names the person again
 * ({@link RegisterRoles}), when the Quarter of its System begins, and when work on its System is handed to the person; a
 * VA POC grant comes back only through configuration ({@link VaPocSync}). Every lapse and return is audited with the
 * grant id, never the person's email.
 *
 * <p>The Quarter's start counts as a return moment for the grants of its System: a grant whose System's Quarter began
 * after it lapsed comes back, and one whose System's Quarter began within the last 90 days does not lapse, so an annual
 * SMO/Tech Lead is not locked out of the Quarter they are named for. The caller says when each System's latest Quarter
 * began, because this module reads neither the Register nor the calendar.
 */
@Service
public class RoleLapse {

    /** How long a grant may go unused before it lapses. */
    static final Duration UNUSED_FOR = Duration.ofDays(90);

    private static final List<Role> PER_SYSTEM = List.of(Role.SMO, Role.CYBER_MANAGER);

    private final RoleGrantStore grants;
    private final AuditLog audit;
    private final AuditActor actor;
    private final Clock clock;

    RoleLapse(RoleGrantStore grants, AuditLog audit, AuditActor actor, Clock clock) {
        this.grants = grants;
        this.audit = audit;
        this.clock = clock;
        this.actor = actor;
    }

    /**
     * One pass of the job: brings back the grants whose System's Quarter began after they lapsed, then lapses every grant
     * unused for 90 days. Idempotent: a second pass finds nothing more to do.
     *
     * @param quarterBegan for each System whose Quarter has begun in a VA Cycle the app runs, the instant its latest one
     *     began (midnight of its first day, business time)
     * @param now the instant the pass read from the clock
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pass(Map<UUID, Instant> quarterBegan, Instant now) {
        for (Role role : PER_SYSTEM) {
            for (RoleGrant grant : grants.lapsed(role, GrantSource.REGISTER)) {
                Instant began = quarterBegan.get(grant.systemId());
                if (began != null && grant.lapsedAt() != null && began.isAfter(grant.lapsedAt())) {
                    returned(grant, ReturnTrigger.QUARTER_START, now);
                }
            }
        }
        Instant cutoff = now.minus(UNUSED_FOR);
        for (RoleGrant grant : grants.unusedSince(cutoff, now)) {
            Instant began = grant.systemId() == null ? null : quarterBegan.get(grant.systemId());
            boolean quarterBeganSince = began != null && !began.isBefore(cutoff);
            if (!quarterBeganSince && grants.lapse(grant.id(), cutoff, now)) {
                audit.record(grant.describe(AuditEvent.of(AuditAction.ROLE_LAPSED, AuditOutcome.SUCCESS)));
            }
        }
    }

    /**
     * Work on a System was handed to whoever holds this role for it: their lapsed grant comes back, in the caller's
     * transaction, attributed to the caller.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handBack(Role role, UUID systemId, ReturnTrigger trigger) {
        Instant now = clock.instant();
        grants.lapsedOn(role, systemId).forEach(grant -> returned(grant, trigger, now));
    }

    /** Brings one lapsed grant back and audits it, unless it came back meanwhile. */
    void returned(RoleGrant grant, ReturnTrigger trigger, Instant now) {
        if (grants.returned(grant.id(), trigger, now)) {
            audit.record(actor.attribute(grant.describe(AuditEvent.of(AuditAction.ROLE_RETURNED, AuditOutcome.SUCCESS)))
                    .with(DetailKey.REASON, trigger.code()));
        }
    }
}
