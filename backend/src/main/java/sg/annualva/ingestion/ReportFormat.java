package sg.annualva.ingestion;

import sg.annualva.finding.ReportType;
import sg.annualva.parser.ReportParser;
import sg.annualva.parser.burp.BurpXmlParser;
import sg.annualva.parser.dependencycheck.DependencyCheckJsonParser;
import sg.annualva.parser.dependencycheck.DependencyCheckXmlParser;
import sg.annualva.parser.nessus.NessusXmlParser;
import sg.annualva.parser.scap.ScapXmlParser;

/**
 * The machine-readable formats the type allowlist accepts, each for one Scan Report and with its own size limit (IM8
 * as-1; ADR 0021) and its own parser. The format is decided from the bytes (the root element of an XML document, the top-level
 * keys of a JSON one), never from the file name or the declared type. Supporting evidence (PDF, HTML) is not a machine-readable
 * format and has none of this: see {@link EvidenceFormat}.
 */
enum ReportFormat {
    DEPENDENCY_CHECK_XML(ReportType.DEPENDENCY_CHECK, 25, new DependencyCheckXmlParser(), false),
    DEPENDENCY_CHECK_JSON(ReportType.DEPENDENCY_CHECK, 25, new DependencyCheckJsonParser(), false),
    /** XCCDF results: a Benchmark or a TestResult. */
    XCCDF_RESULTS(ReportType.SECURE_CONFIGURATION, 25, new ScapXmlParser(), false),
    /** SCAP Asset Reporting Format; larger because it carries the assets and the results together. */
    ARF(ReportType.SECURE_CONFIGURATION, 50, new ScapXmlParser(), false),
    NESSUS(ReportType.SECURE_CONFIGURATION, 50, new NessusXmlParser(), false),
    /** Burp Suite's XML issue export, which records only when it was exported, not when the scan was made. */
    BURP_XML(ReportType.BURP_SUITE, 50, new BurpXmlParser(), true);

    private static final long MEGABYTE = 1024L * 1024L;

    private final ReportType reportType;
    private final long maxBytes;
    private final ReportParser parser;
    private final boolean scanDateIsExportTime;

    ReportFormat(ReportType reportType, long maxMegabytes, ReportParser parser, boolean scanDateIsExportTime) {
        this.reportType = reportType;
        this.maxBytes = maxMegabytes * MEGABYTE;
        this.parser = parser;
        this.scanDateIsExportTime = scanDateIsExportTime;
    }

    /** The parser that reads Findings out of this format. */
    ReportParser parser() {
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

    /** True when the date the file names is only when it was exported, not when the scan was made (Burp). */
    boolean scanDateIsExportTime() {
        return scanDateIsExportTime;
    }
}
