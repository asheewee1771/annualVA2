package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.Samples;
import sg.annualva.parser.ParsedReport;
import tools.jackson.databind.JsonNode;

/**
 * IM8 as-1 for the SCAP parser: a report is untrusted, so what one file may make the server hold is bounded. A report that
 * yields more Findings, or more places they were reported (a rule on a host is a place), than
 * {@link ParsedReport#MAX_FINDINGS} and {@link ParsedReport#MAX_OCCURRENCES} is refused as REJECTED_TOO_MANY_FINDINGS, which
 * says so, with nothing kept; one at the cap is accepted whole. An ARF may be larger than an XCCDF results file (50 MB
 * against 25 MB), and what an ARF carries besides its results (the whole benchmark, which its report requests repeat) is
 * skipped, never read as results.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScapLimitsTest extends ScapSeam1 {

    private static final String SENTINEL = "SENTINEL-6602";

    /** One host with this many failing rules, each its own Finding with one place. */
    private static byte[] hostWithRules(int count, String namePrefix) {
        StringBuilder results = new StringBuilder(count * 90);
        for (int n = 0; n < count; n++) {
            results.append("<rule-result idref=\"").append(namePrefix).append(n)
                    .append("\" severity=\"low\"><result>fail</result></rule-result>");
        }
        return xccdf("<TestResult id=\"t\"><target>host-a.invalid</target>" + results + "</TestResult>");
    }

    /** One rule failing on this many hosts: one Finding with that many places. */
    private static byte[] ruleOnHosts(int count) {
        StringBuilder reports = new StringBuilder(count * 200);
        for (int n = 0; n < count; n++) {
            reports.append("<TestResult id=\"t").append(n).append("\"><target>host-").append(n)
                    .append(".invalid</target><rule-result idref=\"r.everywhere\"><result>fail</result></rule-result>"
                            + "</TestResult>");
        }
        return xccdf(reports.toString());
    }

    @Test
    void aReportAtTheCapOnFindingsIsKeptWhole() {
        Draft draft = draft();

        JsonNode file = settledScap(draft, "cap.xml", hostWithRules(ParsedReport.MAX_FINDINGS, "r."));

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(total(draft, draft.smo())).isEqualTo(ParsedReport.MAX_FINDINGS);
    }

    @Test
    void aReportOverTheCapOnFindingsIsRejectedAsTooManyFindingsAndNothingFromItIsKeptOrSaid(CapturedOutput output) {
        Draft draft = draft();

        JsonNode file = settledScap(draft, "over.xml", hostWithRules(ParsedReport.MAX_FINDINGS + 1, "r." + SENTINEL + "."));

        assertTooManyFindings(file);
        assertThat(fileStore.count(sg.annualva.boundary.FileStore.Zone.CLEAN)).as("no clean copy of it").isZero();
        assertThat(total(draft, draft.smo())).isZero();
        assertThat(draft.smo().get(fileAt(draft.submissionId(), file.get("id").asString()) + "/metadata").status())
                .isEqualTo(404);
        String audit = otherVaPoc().get("/api/audit-entries?size=200").body();
        String errors = draft.smo().get(findingsOf(draft.submissionId()) + "?size=0").body();
        assertThat(file.toString() + audit + errors + draft.smo().get(filesOf(draft.submissionId())).body())
                .as("the status, the audit entries and an error body").doesNotContain(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).contains("REJECTED_TOO_MANY_FINDINGS");
    }

    /** Two hosts, each with this many distinct failing rules (none the same as the other's), so each stays under a host's bound. */
    private static byte[] twoHostsWithRules(int each) {
        StringBuilder results = new StringBuilder(each * 2 * 90);
        for (String host : new String[] {"a", "b"}) {
            results.append("<TestResult id=\"t").append(host).append("\"><target>host-").append(host).append(".invalid</target>");
            for (int n = 0; n < each; n++) {
                results.append("<rule-result idref=\"r.").append(host).append('.').append(n)
                        .append("\"><result>fail</result></rule-result>");
            }
            results.append("</TestResult>");
        }
        return xccdf(results.toString());
    }

    @Test
    void theCapOnFindingsAppliesAcrossHostsToo() {
        Draft draft = draft();

        JsonNode atCap = settledScap(draft, "two-hosts-cap.xml", twoHostsWithRules(ParsedReport.MAX_FINDINGS / 2));
        JsonNode over = settledScap(draft, "two-hosts-over.xml", twoHostsWithRules(ParsedReport.MAX_FINDINGS / 2 + 1));

        assertThat(atCap.get("status").asString()).isEqualTo("PARSED");
        assertTooManyFindings(over);
        assertThat(total(draft, draft.smo())).isEqualTo(ParsedReport.MAX_FINDINGS);
    }

    @Test
    void theCapOnPlacesAppliesToo() {
        Draft draft = draft();

        JsonNode atCap = settledScap(draft, "places.xml", ruleOnHosts(ParsedReport.MAX_OCCURRENCES));
        JsonNode over = settledScap(draft, "more.xml", ruleOnHosts(ParsedReport.MAX_OCCURRENCES + 1));

        assertThat(atCap.get("status").asString()).isEqualTo("PARSED");
        assertTooManyFindings(over);
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactly(
                Map.entry("scap|r.everywhere", "MEDIUM/" + ParsedReport.MAX_OCCURRENCES));
    }

    @Test
    void aRuleResultRepeatedAHundredThousandTimesIsStillOneFindingWithOnePlace() {
        Draft draft = draft();
        StringBuilder results = new StringBuilder();
        for (int n = 0; n < 100_000; n++) {
            results.append(ruleResult("r." + (n % 3), null, "fail"));
        }

        JsonNode file = settledScap(draft, "repeats.xml",
                xccdf("<TestResult id=\"t\"><target>host-a.invalid</target>" + results + "</TestResult>"));

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "scap|r.0", "MEDIUM/1", "scap|r.1", "MEDIUM/1", "scap|r.2", "MEDIUM/1"));
    }

    @Test
    void anArfMayBeLargerThanAnXccdfResultsFileAndWhatItCarriesBesidesItsResultsIsSkipped() {
        Draft draft = draft();
        byte[] arf = arfWithBenchmarkBulk(49 * 1024 * 1024);

        JsonNode file = settledScap(draft, "big-arf.xml", arf);

        assertThat(arf.length).as("over the 25 MB an XCCDF results file may be").isGreaterThan(26 * 1024 * 1024);
        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("ARF");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId())))
                .as("the decoy results in the benchmark bulk are no results").containsExactly(
                        Map.entry("scap|r.real", "MEDIUM/1"));
    }

    @Test
    void anXccdfResultsFileOverItsLimitIsRejectedAsTooLargeBeforeAnythingReadsIt() {
        Draft draft = draft();
        byte[] big = Samples.paddedTo(Samples.cisCatXccdfSample(), 25 * 1024 * 1024 + 1024);

        JsonNode file = settledScap(draft, "big.xml", big);

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_TOO_LARGE");
        assertThat(file.get("message").asString()).contains("larger than the limit for its type");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "REJECTED_TOO_LARGE");
        assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).isZero();
    }

    @Test
    void aSingleValueFarLongerThanAnyRealOneIsRefusedWithoutBeingBuiltWhole() {
        Draft draft = draft();
        String hugeTarget = "<TestResult id=\"t\"><target>" + "h".repeat(8 * 1024 * 1024) + "</target>" + "</TestResult>";

        JsonNode file = settledScap(draft, "huge.xml", xccdf(hugeTarget));

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
    }

    /**
     * An ARF of about this many bytes whose report request carries the benchmark over and over, each time with a decoy
     * TestResult that looks like the real thing, and one real result.
     */
    private static byte[] arfWithBenchmarkBulk(int bytes) {
        String component = "<ds:component id=\"c\"><xccdf:Benchmark id=\"b\"><xccdf:Rule id=\"r.decoy\" severity=\"high\">"
                + "<xccdf:title>t</xccdf:title></xccdf:Rule><xccdf:TestResult id=\"decoy\"><xccdf:target>decoy.invalid"
                + "</xccdf:target><xccdf:rule-result idref=\"r.decoy\"><xccdf:result>fail</xccdf:result>"
                + "</xccdf:rule-result></xccdf:TestResult></xccdf:Benchmark></ds:component>";
        String head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><arf:asset-report-collection xmlns:arf=\"" + ARF_NAMESPACE
                + "\" xmlns:ds=\"http://scap.nist.gov/schema/scap/source/1.2\" xmlns:xccdf=\"" + XCCDF_12 + "\">"
                + "<arf:report-requests><arf:report-request id=\"q\"><arf:content><ds:data-stream-collection id=\"d\">";
        String tail = "</ds:data-stream-collection></arf:content></arf:report-request></arf:report-requests>"
                + "<arf:reports><arf:report id=\"r\"><arf:content>"
                + "<TestResult xmlns=\"" + XCCDF_12 + "\" id=\"real\"><target>real.invalid</target>"
                + "<rule-result idref=\"r.real\"><result>fail</result></rule-result></TestResult>"
                + "</arf:content></arf:report></arf:reports></arf:asset-report-collection>";
        StringBuilder text = new StringBuilder(bytes + 1024);
        text.append(head);
        while (text.length() + tail.length() + component.length() < bytes) {
            text.append(component);
        }
        return text.append(tail).toString().getBytes(StandardCharsets.UTF_8);
    }
}
