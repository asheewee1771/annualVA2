package sg.annualva.register;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;

/**
 * SMO Unreachable (story 13, ADR 0052): what the mail relay answered about an address, kept on the Systems that name it
 * as their SMO/Tech Lead. A refused address flags every active System naming it, a later mail the relay takes clears
 * every flag of it, and a Register apply that adds a System or names another SMO/Tech Lead for it gives it the flag of
 * its new address, or none. The address is matched
 * by the Register's own rules (without regard to letter case), whoever the mail was for: an address the relay refuses
 * is unreachable for every System it is the SMO/Tech Lead of. Each System flagged or cleared is one audit entry, in the
 * caller's transaction, with no address (lm-19). The mail outcomes act for nobody and skip the access guard, so they are
 * never for a request: the architecture test names their caller.
 */
@Service
public class SmoReachability {

    private static final String SOURCE_MAIL = "mail-accepted";
    private static final String SOURCE_REGISTER = "register";

    private final RegisterStore store;
    private final AuditLog audit;
    private final AuditActor actor;

    SmoReachability(RegisterStore store, AuditLog audit, AuditActor actor) {
        this.store = store;
        this.audit = audit;
        this.actor = actor;
    }

    /**
     * The relay refused this address: every active System naming it as its SMO/Tech Lead and not flagged yet is flagged
     * SMO Unreachable from this instant. A System flagged already keeps the time of the first refusal.
     *
     * @param email the address the relay refused; never logged or audited
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refused(String email, Instant at) {
        store.flagUnreachable(email, at).forEach(system -> audit.record(
                AuditEvent.of(AuditAction.SMO_UNREACHABLE, AuditOutcome.SUCCESS).onSystem(system)));
    }

    /**
     * The relay took a mail to this address: every System naming it whose flag was set no later than that is no longer
     * SMO Unreachable. A refusal recorded after this mail went through stands.
     *
     * @param email the address the relay took; never logged or audited
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void accepted(String email, Instant at) {
        store.clearReached(email, at).forEach(system -> audit.record(cleared(system, SOURCE_MAIL)));
    }

    /**
     * A Register apply has just added this System or named another SMO/Tech Lead for it: the flag follows the new address,
     * by the apply's caller. Whatever the old address did says nothing of the new one, so its flag is cleared; and when
     * another active System already names the new address as SMO Unreachable, this one is flagged from the same date, so
     * every System naming a refused address shows it.
     *
     * @param smoEmail the address the System now names; never logged or audited
     */
    void newSmo(UUID systemId, String smoEmail) {
        if (store.clearUnreachable(systemId)) {
            audit.record(actor.attribute(cleared(systemId, SOURCE_REGISTER)));
        }
        if (store.flagAsOthers(systemId, smoEmail)) {
            audit.record(actor.attribute(AuditEvent.of(AuditAction.SMO_UNREACHABLE, AuditOutcome.SUCCESS)
                    .onSystem(systemId).with(DetailKey.SOURCE, SOURCE_REGISTER)));
        }
    }

    private static AuditEvent cleared(UUID systemId, String source) {
        return AuditEvent.of(AuditAction.SMO_UNREACHABLE_CLEARED, AuditOutcome.SUCCESS).onSystem(systemId)
                .with(DetailKey.SOURCE, source);
    }
}
