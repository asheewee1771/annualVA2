package sg.annualva.audit;

/**
 * Names the caller of the current request on an entry the audit module writes about its own reads (ticket 50): the
 * access module answers, because it knows who is signed in and the roles they hold, so the audit log depends on none of
 * the modules that write to it.
 */
public interface AuditCaller {

    /** The same event, by whoever the current request's security context names; unchanged when nobody is signed in. */
    AuditEvent attribute(AuditEvent event);
}
