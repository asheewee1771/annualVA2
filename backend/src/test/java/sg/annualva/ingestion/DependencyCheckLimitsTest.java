package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.parser.ParsedReport;
import tools.jackson.databind.JsonNode;

/**
 * IM8 as-1 for the Dependency-Check parser: a report is untrusted, so what one file may make the server hold is bounded. A
 * report that yields more Findings, or more places they were reported, than {@link ParsedReport#MAX_FINDINGS} and
 * {@link ParsedReport#MAX_OCCURRENCES} is refused as REJECTED_SCHEMA with the usual plain message and nothing kept; one at
 * the cap is accepted whole; and a dependency carrying a very large field the parser has no use for is read without that
 * field being built.
 */
@ExtendWith(OutputCaptureExtension.class)
class DependencyCheckLimitsTest extends DependencyCheckSeam1 {

    private static final String SENTINEL = "SENTINEL-4410";

    /** A report of this many dependencies, each with one vulnerability of its own (so one Finding and one place each). */
    private static byte[] reportOfFindings(int count, String namePrefix) {
        StringBuilder text = new StringBuilder(count * 180 + 200);
        text.append("{\"reportSchema\":\"1.1\",\"scanInfo\":{},\"dependencies\":[");
        for (int n = 0; n < count; n++) {
            text.append(n == 0 ? "" : ",").append("{\"fileName\":\"l").append(n).append(".jar\",\"filePath\":\"/o/l")
                    .append(n).append(".jar\",\"packages\":[{\"id\":\"pkg:maven/e/l").append(n)
                    .append("@1\"}],\"vulnerabilities\":[{\"name\":\"").append(namePrefix).append(n)
                    .append("\",\"cvssv3\":{\"baseScore\":5.0}}]}");
        }
        return text.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One vulnerability of one package found at this many different paths: one Finding, that many places. */
    private static byte[] reportOfPlaces(int count) {
        StringBuilder text = new StringBuilder(count * 150 + 200);
        text.append("{\"reportSchema\":\"1.1\",\"scanInfo\":{},\"dependencies\":[");
        for (int n = 0; n < count; n++) {
            text.append(n == 0 ? "" : ",").append("{\"fileName\":\"a.jar\",\"filePath\":\"/p").append(n)
                    .append("/a.jar\",\"packages\":[{\"id\":\"").append(PURL_TEXT)
                    .append("\"}],\"vulnerabilities\":[{\"name\":\"CVE-2027-1\",\"cvssv3\":{\"baseScore\":5.0}}]}");
        }
        return text.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int total(Draft draft, ApiClient client) {
        return ApiClient.json(client.get(findingsOf(draft.submissionId()) + "?size=1")).get("total").asInt();
    }

    @Test
    void aReportAtTheCapOnFindingsIsKeptWhole() {
        Draft draft = draft();

        JsonNode file = settledDc(draft, "cap.json", reportOfFindings(ParsedReport.MAX_FINDINGS, "CVE-2027-"));

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(total(draft, draft.smo())).isEqualTo(ParsedReport.MAX_FINDINGS);
    }

    @Test
    void aReportOverTheCapOnFindingsIsRejectedAsSchemaAndNothingFromItIsKeptOrSaid(CapturedOutput output) {
        Draft draft = draft();

        JsonNode file = settledDc(draft, "over.json",
                reportOfFindings(ParsedReport.MAX_FINDINGS + 1, "CVE-" + SENTINEL + "-"));

        assertThat(file.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        assertThat(file.get("rejectCode").asString()).isEqualTo("SCHEMA");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK",
                "REJECTED_SCHEMA");
        assertThat(total(draft, draft.smo())).isZero();
        assertThat(draft.smo().get(fileAt(draft.submissionId(), file.get("id").asString()) + "/metadata").status())
                .isEqualTo(404);
        String audit = otherVaPoc().get("/api/audit-entries?size=200").body();
        String errors = draft.smo().get(findingsOf(draft.submissionId()) + "?size=0").body();
        assertThat(file.toString() + audit + errors + draft.smo().get(filesOf(draft.submissionId())).body())
                .as("the status, the audit entries and an error body").doesNotContain(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).contains("REJECTED_SCHEMA");
    }

    @Test
    void theCapOnPlacesAppliesToo() {
        Draft draft = draft();

        JsonNode atCap = settledDc(draft, "places.json", reportOfPlaces(ParsedReport.MAX_OCCURRENCES));
        JsonNode over = settledDc(draft, "more.json", reportOfPlaces(ParsedReport.MAX_OCCURRENCES + 1));

        assertThat(atCap.get("status").asString()).isEqualTo("PARSED");
        assertThat(over.get("status").asString()).isEqualTo("REJECTED_SCHEMA");
        JsonNode page = findings(draft.smo(), draft.submissionId());
        assertThat(summary(page)).containsExactly(
                java.util.Map.entry("dc|CVE-2027-1|" + PURL_TEXT, "MEDIUM/" + ParsedReport.MAX_OCCURRENCES));
    }

    @Test
    void aDependencyWithAVeryLargeFieldTheParserDoesNotUseIsReadWithoutBuildingIt() {
        Draft draft = draft();
        StringBuilder evidence = new StringBuilder("[");
        for (int n = 0; n < 2_000_000; n++) {
            evidence.append(n == 0 ? "" : ",").append("{\"a\":1}");
        }
        evidence.append("]");
        String dependency = "{\"fileName\":\"a.jar\",\"filePath\":\"/o/a.jar\",\"evidenceCollected\":{\"vendorEvidence\":"
                + evidence + "},\"packages\":[{\"id\":\"" + PURL_TEXT + "\"}],\"vulnerabilities\":["
                + jsonVulnerability("CVE-2027-1", v3("5.0")) + "]}";
        byte[] report = jsonReport(dependency);
        assertThat(report.length).as("a field of about 16 MB, inside the 25 MB limit").isBetween(15_000_000, 25_000_000);

        long before = allocatedBytes();
        JsonNode file = settledDc(draft, "evidence.json", report);
        long allocated = allocatedBytes() - before;

        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(summary(findings(draft.smo(), draft.submissionId())))
                .containsExactly(java.util.Map.entry("dc|CVE-2027-1|" + PURL_TEXT, "MEDIUM/1"));
        // Streaming allocates about 170 MB for the whole upload and pipeline (the file is copied a few times); building
        // the field as a tree allocates about 620 MB (measured by reading it with readTree instead).
        assertThat(allocated).as("bytes allocated by the upload and the pipeline").isLessThan(300_000_000L);
    }

    /** What every thread of this JVM has allocated so far: the pipeline runs on the server's threads, not the test's. */
    private static long allocatedBytes() {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long total = 0;
        for (long id : threads.getAllThreadIds()) {
            total += Math.max(0, threads.getThreadAllocatedBytes(id));
        }
        return total;
    }
}
