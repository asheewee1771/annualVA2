package sg.annualva.finding;

/**
 * How a Scan Report of a VA Submission is provided (story 23, ADR 0030). Secure Configuration is always required, so it is
 * uploaded or entered by hand and never not applicable.
 */
public enum ReportMode {
    /** The tool's machine-readable report is uploaded and read for Findings: every Scan Report until its mode is set. */
    UPLOAD,
    /**
     * The Scan Report does not apply to the System (Dependency-Check or Burp Suite only), with a justification the Cyber
     * Manager reviews like a Finding.
     */
    NOT_APPLICABLE,
    /**
     * The tool cannot export a machine-readable report, with a justification: the SMO/Tech Lead types its Findings in, and
     * each is marked Manually Entered.
     */
    MANUAL
}
