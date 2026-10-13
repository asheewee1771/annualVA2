package sg.annualva.ingestion;

/**
 * The machine-readable formats the type allowlist accepts, each for one Scan Report and with its own size limit (IM8
 * as-1; ADR 0021). The format is decided from the bytes (the root element of an XML document, the top-level keys of a
 * JSON one), never from the file name or the declared type. Evidence files (PDF, HTML) have no row yet: ticket 18 adds them.
 */
enum ReportFormat {
    DEPENDENCY_CHECK_XML(ReportType.DEPENDENCY_CHECK, 25),
    DEPENDENCY_CHECK_JSON(ReportType.DEPENDENCY_CHECK, 25),
    /** XCCDF results: a Benchmark or a TestResult. */
    XCCDF_RESULTS(ReportType.SECURE_CONFIGURATION, 25),
    /** SCAP Asset Reporting Format; larger because it carries the assets and the results together. */
    ARF(ReportType.SECURE_CONFIGURATION, 50),
    NESSUS(ReportType.SECURE_CONFIGURATION, 50),
    BURP_XML(ReportType.BURP_SUITE, 50);

    private static final long MEGABYTE = 1024L * 1024L;

    private final ReportType reportType;
    private final long maxBytes;

    ReportFormat(ReportType reportType, long maxMegabytes) {
        this.reportType = reportType;
        this.maxBytes = maxMegabytes * MEGABYTE;
    }

    /** The Scan Report this format belongs to. */
    ReportType reportType() {
        return reportType;
    }

    /** The most a file of this format may weigh. */
    long maxBytes() {
        return maxBytes;
    }
}
