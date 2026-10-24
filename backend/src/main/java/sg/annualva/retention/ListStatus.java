package sg.annualva.retention;

/**
 * Where a disposal list is: written by the retention job and waiting for a VA POC, replaced by a newer one before anyone
 * approved it, approved and waiting for the executor, or executed.
 */
public enum ListStatus {
    DRAFT,
    SUPERSEDED,
    APPROVED,
    EXECUTED
}
