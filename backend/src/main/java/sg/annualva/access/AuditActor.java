package sg.annualva.access;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import sg.annualva.audit.AuditEvent;

/**
 * Names the caller on an audit entry. A domain command that records what it did asks this for the actor, so the entry
 * carries the user id and the roles held right now (never an email or a name, lm-19) without the command learning how
 * the access module resolves them. A request that has not resolved roles, such as one the system itself makes, leaves
 * the entry without an actor.
 */
@Component
public class AuditActor {

    /** The same event, by whoever the current request's security context names. */
    public AuditEvent attribute(AuditEvent event) {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof RoleAuthentication caller) {
            return event.by(caller.getPrincipal().id(), RoleAuthentication.audited(caller.roles()));
        }
        return event;
    }

    /**
     * The same event, by the SMO/Tech Lead whose upload caused what the scan job did on their behalf (ticket 21): the job
     * has no caller of its own, and the change follows from that person's action (reviewer decisions, 2026-10-18). They
     * uploaded it as an SMO/Tech Lead, the only role that uploads, so that is the role recorded.
     */
    public AuditEvent onBehalfOf(AuditEvent event, UUID uploadedBy) {
        return event.by(uploadedBy, Role.SMO.name());
    }
}
