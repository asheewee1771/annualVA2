package sg.annualva.audit;

/**
 * Who may read which entries, answered by the module that knows who reviewed what (the VA Submissions), so the audit
 * log depends on none of the modules that write to it (ADR 0038).
 */
public interface AuditReaders {

    /** The entries the caller of this request may read. */
    AuditReadScope scopeOfCaller();
}
