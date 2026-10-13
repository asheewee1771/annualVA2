package sg.annualva.finding;

/** The three Scan Reports every VA Submission holds (CONTEXT.md); Secure Configuration is always required. */
public enum ReportType {
    /** OWASP Dependency-Check. */
    DEPENDENCY_CHECK,
    /** Secure Configuration: a SCAP ARF or XCCDF result, or a Nessus compliance audit. */
    SECURE_CONFIGURATION,
    /** Burp Suite. */
    BURP_SUITE
}
