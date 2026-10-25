package sg.annualva.retention;

/**
 * What one item of a disposal list disposes of (V149 {@code disposal_item.kind}).
 *
 * <ul>
 *   <li>{@code SUBMISSION}: a VA Submission and everything that hangs off it
 *   <li>{@code FILE}: a Scan Report file of such a VA Submission, its content and its row
 *   <li>{@code SUPERSEDED_FILE}: a superseded file's content only; its row stays until its Cycle is disposed of
 *   <li>{@code EXPORT}: an export's row, whose file the export job removed when its 24 hours were up
 *   <li>{@code SYSTEM}: a retired System's row
 *   <li>{@code CYCLE}: the records of a Cycle that are about no one VA Submission
 *   <li>{@code AUDIT_RANGE}: the audit entries written before the list's cutoff
 * </ul>
 */
public enum ItemKind {
    SUBMISSION,
    FILE,
    SUPERSEDED_FILE,
    EXPORT,
    SYSTEM,
    CYCLE,
    AUDIT_RANGE
}
