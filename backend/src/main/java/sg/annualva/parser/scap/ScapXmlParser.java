package sg.annualva.parser.scap;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLStreamException;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.FindingKeys;
import sg.annualva.parser.FindingsBuilder;
import sg.annualva.parser.ParsedReport;
import sg.annualva.parser.ReportDates;
import sg.annualva.parser.ReportParser;
import sg.annualva.parser.ReportText;
import sg.annualva.parser.ScanFacts;
import sg.annualva.parser.ScanReading;
import sg.annualva.parser.Severity;
import sg.annualva.parser.UnparseableReportException;
import sg.annualva.parser.xml.XmlCursor;
import sg.annualva.parser.xml.XmlCursor.Boundary;

/**
 * Reads the results of a SCAP scan, from CIS-CAT Pro or OpenSCAP, in either of the two shapes they export: an XCCDF 1.1 or 1.2
 * {@code Benchmark} that carries its {@code TestResult}s (or a bare {@code TestResult}), and an ARF
 * ({@code asset-report-collection}) whose reports each hold one. Each {@code TestResult} is one host's run. The document is
 * streamed and only the results are read, where the formats put them ({@code TestResult}, {@code Benchmark/TestResult},
 * {@code asset-report-collection/reports/report/content/TestResult}); everything else, an ARF's report requests (which
 * repeat the whole benchmark and are why an ARF is so large) included, is skipped without being looked at, so a result-like
 * element anywhere else is never mistaken for one.
 *
 * <p>A rule result of {@code fail} is a Finding keyed {@code scap|<rule idref>}; {@code error} and {@code unknown} are a
 * Finding too, marked as a check that did not complete, because they are neither a pass nor a fail. Every other result
 * ({@code pass}, {@code notapplicable}, {@code notchecked}, {@code notselected}, {@code informational}, {@code fixed}) is
 * ignored. A Finding has one occurrence per host (the {@code target}, else the first {@code target-address}, else the id of
 * its {@code TestResult}). The severity is the one the rule result carries ({@code high}, {@code medium}, {@code low},
 * {@code info}); {@code unknown}, none or any other word is Medium. The Benchmark's own {@code Rule} severity is not read:
 * CIS-CAT writes none, OpenSCAP copies it onto the result, and reading it would mean holding every rule of the benchmark.
 *
 * <p>The scan date offered is the latest {@code end-time} of the file's results and the target the hosts they name. A file
 * that is neither shape, has no {@code TestResult} where one belongs, has a result with no rule id, or has a value longer than
 * {@link ReportText#MAX_FIELD_CHARS} is refused; one that yields more than a file may hold is refused as too large.
 */
public final class ScapXmlParser implements ReportParser {

    private static final String ARF_NAMESPACE = "http://scap.nist.gov/schema/asset-reporting-format/";
    private static final String XCCDF_NAMESPACE = "http://checklists.nist.gov/xccdf/1.";

    private static final String ARF_ROOT = "asset-report-collection";
    private static final String BENCHMARK_ROOT = "Benchmark";
    private static final String TEST_RESULT = "TestResult";
    private static final String ARF_REPORTS = ARF_ROOT + "/reports";
    private static final String ARF_REPORT = ARF_REPORTS + "/report";
    private static final String ARF_CONTENT = ARF_REPORT + "/content";

    /** Where a TestResult is: bare, in a Benchmark, or in the content of an ARF report. */
    private static final Set<String> TEST_RESULTS = Set.of(TEST_RESULT, BENCHMARK_ROOT + "/" + TEST_RESULT,
            ARF_CONTENT + "/" + TEST_RESULT);
    /** The elements on the way to a TestResult, the only ones the walk goes into. */
    private static final Set<String> WAY_IN = Set.of(ARF_ROOT, ARF_REPORTS, ARF_REPORT, ARF_CONTENT, BENCHMARK_ROOT);

    private static final int MAX_RULE_ID = 500;
    /**
     * The most distinct rules one TestResult may gather before it ends: a per-item bound on what is held while a host's
     * results are read. The caps on Findings and places for the whole file belong to {@link FindingsBuilder}.
     */
    private static final int MAX_RULES_PER_HOST = 20_000;
    private static final int MAX_SHORT_VALUE = 100;

    /** The place of a reading whose host is not yet known; {@link ParsedReport.Occurrence#at} puts it on its host. */
    private static final String HOST_TO_COME = "";

    @Override
    public ParsedReport parse(byte[] content) throws UnparseableReportException {
        return XmlCursor.read(content, ScapXmlParser::read);
    }

    private static ParsedReport read(XmlCursor xml) throws XMLStreamException, UnparseableReportException {
        ScanReading reading = new ScanReading();
        Boundary boundary;
        while ((boundary = xml.next()) != null) {
            if (boundary == Boundary.END) {
                continue;
            }
            String path = xml.path();
            if (xml.depth() == 1) {
                requireScapRoot(xml, path);
            }
            if (TEST_RESULTS.contains(path) && isXccdf(xml.namespace())) {
                readTestResult(xml, reading);
            } else if (!WAY_IN.contains(path)) {
                xml.skipElement();
            }
        }
        // A Benchmark with no results, or an ARF with none, is not a results file: building the reading refuses it.
        return reading.build();
    }

    /** The root must be an ARF, or an XCCDF Benchmark or TestResult; the type check looked at the name only. */
    private static void requireScapRoot(XmlCursor xml, String root) throws UnparseableReportException {
        String namespace = xml.namespace();
        boolean arf = ARF_ROOT.equals(root) && namespace != null && namespace.startsWith(ARF_NAMESPACE);
        boolean xccdf = (BENCHMARK_ROOT.equals(root) || TEST_RESULT.equals(root)) && isXccdf(namespace);
        if (!arf && !xccdf) {
            throw new UnparseableReportException();
        }
    }

    private static boolean isXccdf(@Nullable String namespace) {
        return namespace != null && namespace.startsWith(XCCDF_NAMESPACE);
    }

    /**
     * One host's TestResult, the cursor on its start; it is left after its end. The rule results are gathered first, one
     * verdict per rule (a rule read a hundred thousand times is still one), because the host they belong to may be named after
     * them, and handed to the Findings when the TestResult ends.
     */
    private static void readTestResult(XmlCursor xml, ScanReading reading) throws XMLStreamException,
            UnparseableReportException {
        String root = xml.path();
        String id = xml.attribute("id", ReportText.MAX_FIELD_CHARS);
        Instant endTime = ReportDates.parse(xml.attribute("end-time", MAX_SHORT_VALUE));
        @Nullable String target = null;
        @Nullable String address = null;
        Map<String, ParsedReport.Occurrence> verdicts = new LinkedHashMap<>();
        while (xml.nextChild()) {
            switch (xml.below(root)) {
                case "/target" -> target = ReportText.firstClean(target, xml.text(ReportText.MAX_FIELD_CHARS));
                case "/target-address" -> address = ReportText.firstClean(address, xml.text(ReportText.MAX_FIELD_CHARS));
                case "/rule-result" -> readRuleResult(xml, verdicts);
                default -> xml.skipElement();
            }
        }
        reading.sawSection();
        reading.facts().scanned(endTime);
        String host = ReportText.firstClean(target, address);
        if (host != null) {
            reading.facts().named(host);
        }
        String place = host != null ? host : ReportText.clean(id).orElse(null);
        if (!verdicts.isEmpty() && (place == null || !ReportText.fits(place, ScanFacts.MAX_HOST_CHARS))) {
            throw new UnparseableReportException();
        }
        for (Map.Entry<String, ParsedReport.Occurrence> verdict : verdicts.entrySet()) {
            String rule = verdict.getKey();
            reading.findings().found(FindingKeys.scap(rule), rule, verdict.getValue().at(place));
        }
    }

    /** One rule result, the cursor on its start; it is left after its end. Only a failed or an incomplete check is kept. */
    private static void readRuleResult(XmlCursor xml, Map<String, ParsedReport.Occurrence> verdicts) throws XMLStreamException,
            UnparseableReportException {
        String ruleResult = xml.path();
        String idref = xml.attribute("idref", ReportText.MAX_FIELD_CHARS);
        String severity = xml.attribute("severity", MAX_SHORT_VALUE);
        @Nullable String result = null;
        while (xml.nextChild()) {
            if (xml.path().equals(ruleResult + "/result")) {
                result = xml.text(MAX_SHORT_VALUE).strip().toLowerCase(Locale.ROOT);
            } else {
                xml.skipElement();
            }
        }
        boolean didNotComplete = "error".equals(result) || "unknown".equals(result);
        if (!didNotComplete && !"fail".equals(result)) {
            return;
        }
        String rule = ReportText.clean(idref).orElseThrow(UnparseableReportException::new);
        if (!ReportText.fits(rule, MAX_RULE_ID)) {
            throw new UnparseableReportException();
        }
        verdicts.merge(rule, new ParsedReport.Occurrence(HOST_TO_COME, Severity.ofScap(severity), didNotComplete),
                ParsedReport.Occurrence::merge);
        if (verdicts.size() > MAX_RULES_PER_HOST) {
            throw UnparseableReportException.overALimit();
        }
    }
}
