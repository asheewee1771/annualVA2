package sg.annualva.access;

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
}
