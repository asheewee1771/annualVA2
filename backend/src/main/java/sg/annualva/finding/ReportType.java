package sg.annualva.finding;

/** The three Scan Reports every VA Submission holds (CONTEXT.md); Secure Configuration is always required. */
public enum ReportType {
    /** OWASP Dependency-Check. */
    DEPENDENCY_CHECK("Dependency-Check"),
    /** Secure Configuration: a SCAP ARF or XCCDF result, or a Nessus compliance audit. */
    SECURE_CONFIGURATION("Secure Configuration"),
    /** Burp Suite. */
    BURP_SUITE("Burp Suite");

    private final String title;

    ReportType(String title) {
        this.title = title;
    }

    /** Its name in a fixed sentence, as the screens show it. */
    String title() {
        return title;
    }
}
