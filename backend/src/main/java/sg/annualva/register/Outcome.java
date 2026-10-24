package sg.annualva.register;

/**
 * What an upload would do with one row of the file or with one System it leaves out. A row has exactly one outcome:
 * BLOCKED when it breaks a rule (its reasons say which), otherwise ADD, UPDATE or NO_CHANGE by System ID. A System is
 * never deleted.
 */
public enum Outcome {
    ADD,
    UPDATE,
    /** The System is no longer listed: it is marked retired and keeps its history. */
    RETIRE,
    NO_CHANGE,
    /**
     * The row breaks a rule of the file; while any row is BLOCKED the preview cannot be applied. A RETIRE or an UPDATE its
     * in-flight work holds back keeps its outcome and carries its reasons instead (ADR 0048).
     */
    BLOCKED
}
