package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Stories 28, 78 and 84 and IM8 lm-19: {@code GET /api/submissions/{id}/findings} lists the Findings read from a VA
 * Submission's reports for anyone who may see its System, the most severe first and in pages. A VA Submission the caller may
 * not see is 404, as one that does not exist is. Every read of Findings or of what a report said about the scan is an audit
 * entry for every role, holding ids and counts only, and nothing read from a report reaches a log line or an error body.
 */
@ExtendWith(OutputCaptureExtension.class)
class FindingsListTest extends DependencyCheckSeam1 {

    private static final String SENTINEL = "SENTINEL-7731";

    /** The entries of this action about this System, read as the second VA POC. */
    private JsonNode entries(String action, String system) {
        var response = otherVaPoc().get("/api/audit-entries?size=200&action=" + action + "&system=" + system);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    private static void assertNotFound(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/not-found");
    }

    @Test
    void theSmoTheCyberManagerAndTheVaPocReadTheFindingsAndAStrangerIsToldNotFound() {
        Draft draft = draft();
        settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        for (ApiClient reader : List.of(draft.smo(), signedInAs(CM), vaPoc())) {
            assertThat(summary(findings(reader, draft.submissionId()))).containsExactlyEntriesOf(sampleFindings());
        }
        var missing = draft.smo().get(findingsOf("00000000-0000-4000-8000-000000000000"));
        for (ApiClient stranger : List.of(signedInAs(SMO_B), signedInAs(CM_OTHER))) {
            var refused = stranger.get(findingsOf(draft.submissionId()));
            assertNotFound(refused);
            assertThat(ApiClient.json(refused).get("detail")).as("the same answer a missing VA Submission gets")
                    .isEqualTo(ApiClient.json(missing).get("detail"));
        }
        assertThat(api.get(findingsOf(draft.submissionId())).status()).as("not signed in").isEqualTo(401);
    }

    @Test
    void theListIsPagedAndAnOutOfRangePageOrSizeIsRefused() {
        Draft draft = draft();
        settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());

        JsonNode first = ApiClient.json(draft.smo().get(findingsOf(draft.submissionId()) + "?page=0&size=3"));
        JsonNode second = ApiClient.json(draft.smo().get(findingsOf(draft.submissionId()) + "?page=1&size=3"));
        JsonNode third = ApiClient.json(draft.smo().get(findingsOf(draft.submissionId()) + "?page=2&size=3"));
        JsonNode beyond = ApiClient.json(draft.smo().get(findingsOf(draft.submissionId()) + "?page=3&size=3"));

        assertThat(first.get("total").asInt()).isEqualTo(7);
        assertThat(first.get("size").asInt()).isEqualTo(3);
        assertThat(first.get("items")).hasSize(3);
        assertThat(second.get("items")).hasSize(3);
        assertThat(third.get("items")).hasSize(1);
        assertThat(beyond.get("items")).isEmpty();
        List<String> keys = new ArrayList<>();
        for (JsonNode page : List.of(first, second, third)) {
            page.get("items").forEach(item -> keys.add(item.get("identityKey").asString()));
        }
        assertThat(keys).as("the pages follow one another with nothing repeated or missing")
                .containsExactlyElementsOf(sampleFindings().keySet());
        for (String bad : List.of("?size=0", "?size=501", "?page=-1")) {
            assertThat(draft.smo().get(findingsOf(draft.submissionId()) + bad).status()).as(bad).isEqualTo(400);
        }
    }

    @Test
    void aVaSubmissionWithNoReportReadYetHasAnEmptyList() {
        Draft draft = draft();

        JsonNode page = findings(draft.smo(), draft.submissionId());

        assertThat(page.get("total").asInt()).isZero();
        assertThat(page.get("items")).isEmpty();
    }

    @Test
    void everyReadOfFindingsIsOneEntryForEveryRoleHoldingIdsAndACountOnly() {
        Draft draft = draft();
        String id = settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml()).get("id").asString();
        ApiClient manager = signedInAs(CM);

        findings(draft.smo(), draft.submissionId());
        findings(manager, draft.submissionId());
        draft.smo().get(findingsOf(draft.submissionId()) + "?size=3");
        assertNotFound(signedInAs(SMO_B).get(findingsOf(draft.submissionId())));

        JsonNode entries = entries("FINDINGS_VIEWED", draft.systemId());
        assertThat(entries).as("the owner's reads too, and not the refused one").hasSize(3);
        assertThat(entries.get(0).get("detail").get("count").asInt()).isEqualTo(3);
        assertThat(entries.get(1).get("actorRole").asString()).isEqualTo("CYBER_MANAGER");
        assertThat(entries.get(1).get("detail").get("count").asInt()).isEqualTo(7);
        assertThat(entries.get(2).get("actorRole").asString()).isEqualTo("SMO");
        for (JsonNode entry : entries) {
            assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(entry.get("systemId").asString()).isEqualTo(draft.systemId());
            assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
            assertThat(entry.get("detail").propertyNames()).containsExactly("count");
            assertThat(entry.toString()).doesNotContain("CVE-").doesNotContain("pkg:").doesNotContain("log4j")
                    .doesNotContain("legacy").doesNotContain("throwaway");
        }
        assertThat(id).isNotBlank();
    }

    @Test
    void readingWhatAReportSaidAboutTheScanIsAnEntryForEveryRoleToo() {
        Draft draft = draft();
        String id = settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml()).get("id").asString();

        metadata(draft.smo(), draft.submissionId(), id);
        metadata(signedInAs(CM), draft.submissionId(), id);
        assertNotFound(signedInAs(SMO_B).get(fileAt(draft.submissionId(), id) + "/metadata"));

        JsonNode entries = entries("FILE_METADATA_VIEWED", draft.systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("actorRole").asString()).isEqualTo("CYBER_MANAGER");
        assertThat(entries.get(1).get("actorRole").asString()).isEqualTo("SMO");
        for (JsonNode entry : entries) {
            assertThat(entry.get("detail").get("file_id").asString()).isEqualTo(id);
            assertThat(entry.get("detail").propertyNames()).containsExactly("file_id");
            assertThat(entry.toString()).doesNotContain("throwaway").doesNotContain("2027-07-20");
        }
    }

    @Test
    void nothingReadFromAReportReachesALogLineAnAuditEntryOrAnErrorBody(CapturedOutput output) {
        Draft draft = draft();
        byte[] accepted = jsonReportOf("{\"name\":\"" + SENTINEL + "-project\",\"reportDate\":\"2027-07-20T10:00:00Z\"}",
                jsonDependency(SENTINEL + ".jar", "/opt/" + SENTINEL + "/a.jar", "pkg:maven/" + SENTINEL + "/a@1",
                        jsonVulnerability("CVE-" + SENTINEL, v3("9.9"))));
        byte[] refused = jsonReport(jsonDependency(SENTINEL + ".jar", "/opt/" + SENTINEL + "/a.jar",
                "pkg:maven/" + SENTINEL + "/a@1", "{\"name\":\"" + SENTINEL + "|x\"}"));
        String id = settledDc(draft, "one.json", accepted).get("id").asString();
        JsonNode rejected = settledDc(draft, "two.json", refused);

        JsonNode page = findings(draft.smo(), draft.submissionId());
        JsonNode metadata = metadata(draft.smo(), draft.submissionId(), id);
        var stranger = signedInAs(SMO_B).get(findingsOf(draft.submissionId()));
        List<String> bodies = List.of(stranger.body(), rejected.toString(), draft.smo().get(filesOf(draft.submissionId())).body(),
                draft.smo().get(fileAt(draft.submissionId(), id)).body(),
                draft.smo().get(findingsOf(draft.submissionId()) + "?size=0").body());
        // The suite shares one audit log and other tests write entries dated later, so this System's entries are read
        // by System to be sure they are there; the newest page of the whole log is checked as well.
        String audit = otherVaPoc().get("/api/audit-entries?size=200&system=" + draft.systemId()).body();

        assertThat(page.toString()).as("Findings are data, so they are returned").contains(SENTINEL);
        assertThat(metadata.toString()).contains(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).contains("PARSED").contains("REJECTED_SCHEMA");
        assertThat(audit).as("the System's audit entries").doesNotContain(SENTINEL).contains("FILE_UPLOADED")
                .contains("FILE_REJECTED").contains("FILE_METADATA_VIEWED").contains("FINDINGS_VIEWED");
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).as("the audit log").doesNotContain(SENTINEL);
        assertThat(String.join("\n", bodies)).as("error bodies, statuses and lists").doesNotContain(SENTINEL);
    }

    @Test
    void aFileIsNeverParsedWithoutItsFindingsOrTheOtherWayRound() {
        Draft draft = draft();
        String id = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "dc.xml",
                Samples.dependencyCheckSampleXml()).get("id").asString();
        pass();
        // The last write of the settlement fails: the file row, its stage rows and its Findings were all written before it.
        arrange.findingFault("on");
        try {
            pass();

            JsonNode file = status(draft.smo(), draft.submissionId(), id);
            assertThat(file.get("status").asString()).as("the whole outcome was rolled back").isEqualTo("SCAN_SUBMITTED");
            assertThat(stages(file)).as("no stage of the failed attempt was kept")
                    .containsExactly("QUARANTINED", "SCAN_SUBMITTED");
            assertThat(findings(draft.smo(), draft.submissionId()).get("total").asInt()).as("no Finding was kept")
                    .isZero();
            assertThat(fileStore.count(sg.annualva.boundary.FileStore.Zone.CLEAN)).as("nor a clean copy").isZero();
        } finally {
            arrange.findingFault("off");
        }

        pass();

        JsonNode file = status(draft.smo(), draft.submissionId(), id);
        assertThat(file.get("status").asString()).isEqualTo("PARSED");
        assertThat(stages(file)).containsExactly("QUARANTINED", "SCAN_SUBMITTED", "SCAN_CLEAN", "TYPE_OK", "SCHEMA_OK",
                "PARSED");
        assertThat(summary(findings(draft.smo(), draft.submissionId()))).as("one set of Findings and places, not two")
                .containsExactlyEntriesOf(sampleFindings());
    }
}
