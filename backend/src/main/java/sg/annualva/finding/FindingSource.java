package sg.annualva.finding;

/** Where a Finding came from (story 23). */
public enum FindingSource {
    /** Read from a Scan Report file by its parser. */
    PARSED,
    /**
     * Typed in by the SMO/Tech Lead because the tool cannot export a machine-readable report: always shown as Manually
     * Entered, for the Cyber Manager to check more closely (story 52).
     */
    MANUAL
}
