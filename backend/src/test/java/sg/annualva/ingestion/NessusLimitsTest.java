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
 * IM8 as-1 for the Nessus parser: a report is untrusted, so what one file may make the server hold is bounded. A report that
 * yields more Findings, or more places they were reported (a check on a host is a place), than
 * {@link ParsedReport#MAX_FINDINGS} and {@link ParsedReport#MAX_OCCURRENCES} is refused as REJECTED_TOO_MANY_FINDINGS, which
 * says so, with nothing kept; one at the cap is accepted whole. A {@code .nessus} file may be as large as 50 MB, nearly all of
 * it descriptions and plugin output that are skipped, never read.
 */
@ExtendWith(OutputCaptureExtension.class)
class NessusLimitsTest extends NessusSeam1 {

    private static final String SENTINEL = "SENTINEL-8813";

    /** A failed check with only what the parser reads. */
    private static String lean(String id) {
        return "<ReportItem severity=\"3\"><cm:compliance-check-id>" + id + "</cm:compliance-check-id>"
                + "<cm:compliance-result>FAILED</cm:compliance-result></ReportItem>";
    }

    /** One host with this many failed checks, each its own Finding with one place. */
    private static byte[] hostWithChecks(int count, String idPrefix) {
        StringBuilder items = new StringBuilder(count * 120);
        for (int n = 0; n < count; n++) {
            items.append(lean(idPrefix + n));
        }
        return nessus("<ReportHost name=\"host-a\">" + items + "</ReportHost>");
    }

    /** Two hosts, each with this many distinct failed checks (none the same as the other's). */
    private static byte[] twoHostsWithChecks(int each) {
        StringBuilder hosts = new StringBuilder(each * 2 * 120);
        for (String host : new String[] {"a", "b"}) {
            hosts.append("<ReportHost name=\"host-").append(host).append("\">");
            for (int n = 0; n < each; n++) {
                hosts.append(lean("c." + host + "." + n));
            }
            hosts.append("</ReportHost>");
        }
        return nessus(hosts.toString());
    }

    /** One check failing on this many hosts: one Finding with that many places. */
    private static byte[] checkOnHosts(int count) {
        StringBuilder hosts = new StringBuilder(count * 200);
        for (int n = 0; n < count; n++) {
            hosts.append("<ReportHost name=\"host-").append(n).append("\">").append(lean("c.everywhere"))
                    .append("</ReportHost>");
        }
        return nessus(hosts.toString());
    }

    @Test
    void aReportAtTheCapOnFindingsIsKeptWhole() {
        Draft draft = draft();

        JsonNode file = settledNessus(draft, "cap.nessus", hostWithChecks(ParsedReport.MAX_FINDINGS, "c."));

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(total(draft, draft.smo())).isEqualTo(ParsedReport.MAX_FINDINGS);
    }

    @Test
    void aReportOverTheCapOnFindingsIsRejectedAsTooManyFindingsAndNothingFromItIsKeptOrSaid(CapturedOutput output) {
        Draft draft = draft();

        JsonNode file = settledNessus(draft, "over.nessus", hostWithChecks(ParsedReport.MAX_FINDINGS + 1, "c." + SENTINEL + "."));

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

    @Test
    void theCapOnFindingsAppliesAcrossHostsToo() {
        Draft draft = draft();

        JsonNode atCap = settledNessus(draft, "two-hosts-cap.nessus", twoHostsWithChecks(ParsedReport.MAX_FINDINGS / 2));
        JsonNode over = settledNessus(draft, "two-hosts-over.nessus", twoHostsWithChecks(ParsedReport.MAX_FINDINGS / 2 + 1));

        assertThat(atCap.get("status").asString()).isEqualTo("PARSED");
        assertTooManyFindings(over);
        assertThat(total(draft, draft.smo())).isEqualTo(ParsedReport.MAX_FINDINGS);
    }

    @Test
    void theCapOnPlacesAppliesToo() {
        Draft draft = draft();

        JsonNode atCap = settledNessus(draft, "places.nessus", checkOnHosts(ParsedReport.MAX_OCCURRENCES));
        JsonNode over = settledNessus(draft, "more.nessus", checkOnHosts(ParsedReport.MAX_OCCURRENCES + 1));

        assertThat(atCap.get("status").asString()).isEqualTo("PARSED");
        assertTooManyFindings(over);
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactly(
                Map.entry("nessus|c.everywhere", "HIGH/" + ParsedReport.MAX_OCCURRENCES));
    }

    @Test
    void aCheckResultRepeatedAHundredThousandTimesForOneHostIsStillOneFindingWithOnePlace() {
        Draft draft = draft();
        StringBuilder items = new StringBuilder();
        for (int n = 0; n < 100_000; n++) {
            items.append(lean("c." + (n % 3)));
        }

        JsonNode file = settledNessus(draft, "repeats.nessus", nessus("<ReportHost name=\"host-a\">" + items + "</ReportHost>"));

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId()))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "nessus|c.0", "HIGH/1", "nessus|c.1", "HIGH/1", "nessus|c.2", "HIGH/1"));
    }

    @Test
    void aNessusFileMayBeAsLargeAsItsLimitAndWhatItCarriesBesidesItsChecksIsSkipped() {
        Draft draft = draft();
        byte[] big = nessusWithBulk(49 * 1024 * 1024);

        JsonNode file = settledNessus(draft, "big.nessus", big);

        assertThat(big.length).as("over the 25 MB an XCCDF results file may be").isGreaterThan(26 * 1024 * 1024);
        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(file.get("detectedFormat").asString()).isEqualTo("NESSUS");
        assertThat(findingSummary(findings(draft.smo(), draft.submissionId())))
                .as("the decoys in the bulk are no results").containsExactly(Map.entry("nessus|c.real", "HIGH/1"));
    }

    @Test
    void aNessusFileOverItsLimitIsRefusedAtTheUploadAndNothingIsKept() {
        Draft draft = draft();
        byte[] big = Samples.paddedTo(Samples.nessusComplianceSample(), 50 * 1024 * 1024 + 1024);

        var response = upload(draft.smo(), draft.submissionId(), SECURE_CONFIGURATION, MACHINE_READABLE, "big.nessus", big);

        assertThat(response.status()).as("its 50 MB limit is the request ceiling (ADR 0021)").isEqualTo(413);
        assertThat(fileStore.count(sg.annualva.boundary.FileStore.Zone.QUARANTINE)).isZero();
        assertThat(sg.annualva.harness.ApiClient.json(draft.smo().get(filesOf(draft.submissionId())))).isEmpty();
    }

    @Test
    void aSingleValueFarLongerThanAnyRealOneIsRefusedWithoutBeingBuiltWhole() {
        Draft draft = draft();
        byte[] report = nessus(host("host-a", null, check("c", "n".repeat(8 * 1024 * 1024), "FAILED", "3")));

        JsonNode file = settledNessus(draft, "huge.nessus", report);

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
    }

    /**
     * A Nessus export of about this many bytes: one real failed check, and then host after host of items that are not
     * compliance checks and carry long plugin output, some of which look like compliance results but are not where one belongs.
     */
    private static byte[] nessusWithBulk(int bytes) {
        String output = "o".repeat(100_000);
        String bulk = "<ReportItem severity=\"3\" pluginID=\"1\"><description>d</description><plugin_output>" + output
                + "</plugin_output><extra><cm:compliance-check-id>c.decoy</cm:compliance-check-id>"
                + "<cm:compliance-result>FAILED</cm:compliance-result></extra></ReportItem>";
        String head = "<?xml version=\"1.0\" ?><NessusClientData_v2><Policy/>"
                + "<Report name=\"r\" xmlns:cm=\"http://www.nessus.org/cm\"><ReportHost name=\"host-a\">" + lean("c.real");
        String tail = "</ReportHost></Report></NessusClientData_v2>";
        StringBuilder text = new StringBuilder(bytes + 1024);
        text.append(head);
        while (text.length() + tail.length() + bulk.length() < bytes) {
            text.append(bulk);
        }
        return text.append(tail).toString().getBytes(StandardCharsets.UTF_8);
    }
}
