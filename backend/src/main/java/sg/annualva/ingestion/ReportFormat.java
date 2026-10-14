package sg.annualva.ingestion;

import org.jspecify.annotations.Nullable;
import sg.annualva.finding.ReportType;
import sg.annualva.parser.ReportParser;
import sg.annualva.parser.dependencycheck.DependencyCheckJsonParser;
import sg.annualva.parser.dependencycheck.DependencyCheckXmlParser;
import sg.annualva.parser.scap.ScapXmlParser;

/**
 * The machine-readable formats the type allowlist accepts, each for one Scan Report and with its own size limit (IM8
 * as-1; ADR 0021). The format is decided from the bytes (the root element of an XML document, the top-level keys of a
 * JSON one), never from the file name or the declared type. Evidence files (PDF, HTML) have no row yet: ticket 18 adds them.
 */
enum ReportFormat {
    DEPENDENCY_CHECK_XML(ReportType.DEPENDENCY_CHECK, 25, new DependencyCheckXmlParser()),
    DEPENDENCY_CHECK_JSON(ReportType.DEPENDENCY_CHECK, 25, new DependencyCheckJsonParser()),
    /** XCCDF results: a Benchmark or a TestResult. */
    XCCDF_RESULTS(ReportType.SECURE_CONFIGURATION, 25, new ScapXmlParser()),
    /** SCAP Asset Reporting Format; larger because it carries the assets and the results together. */
    ARF(ReportType.SECURE_CONFIGURATION, 50, new ScapXmlParser()),
    NESSUS(ReportType.SECURE_CONFIGURATION, 50, null),
    BURP_XML(ReportType.BURP_SUITE, 50, null);

    private static final long MEGABYTE = 1024L * 1024L;

    private final ReportType reportType;
    private final long maxBytes;
    private final @Nullable ReportParser parser;

    ReportFormat(ReportType reportType, long maxMegabytes, @Nullable ReportParser parser) {
        this.reportType = reportType;
        this.maxBytes = maxMegabytes * MEGABYTE;
        this.parser = parser;
    }

    /** The parser that reads Findings out of this format; null until the ticket for it (16 or 17) adds one. */
    @Nullable ReportParser parser() {
        return parser;
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
