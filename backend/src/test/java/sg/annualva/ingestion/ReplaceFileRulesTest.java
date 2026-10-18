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
 * Who may replace a Scan Report file and which file can be replaced (story 39, ticket 21), and what the audit log says.
 * Only the System's SMO/Tech Lead replaces (another SMO gets the 404 a missing file gets, ADR 0013; a Cyber Manager or VA
 * POC 403), only while the VA Submission takes files, only a parsed report file that no rescan has replaced, and one
 * rescan of a file at a time. The rescan's upload is the SMO's {@code FILE_UPLOADED} entry naming the file it replaces;
 * the replacement itself is the scan job's {@code FILE_REPLACED} entry, and each Remediated proposal a rescan makes is a
 * {@code STATUS_PROPOSED} entry; both name the uploader as the actor. Entries hold ids and fixed codes only.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReplaceFileRulesTest extends ReplaceSeam1 {

    private static final String MISSING = "00000000-0000-4000-8000-000000000000";

    private static void assertConflict(ApiClient.Response response, String type) {
        assertThat(response.status()).as(response.body()).isEqualTo(409);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/" + type);
    }

    /** The reasons of the refused uploads about this System, newest first. */
    private List<String> refusals(String systemId) {
        List<String> reasons = new ArrayList<>();
        for (JsonNode entry : entries("FILE_UPLOADED", systemId)) {
            if (entry.get("outcome").asString().equals("REFUSED")) {
                reasons.add(entry.get("detail").get("reason").asString());
            }
        }
        return reasons;
    }

    @Test
    void onlyTheSystemsSmoTechLeadReplacesAndOnlyAFileOfThatVaSubmission() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        ApiClient smoB = signedInAs(SMO_B);
        String otherSubmission = started(smoB, idOf("SYS-F2")).get("id").asString();
        String otherFile = uploaded(smoB, otherSubmission, DEPENDENCY_CHECK, "other.xml", Samples.dependencyCheckXml())
                .get("id").asString();

        assertNotFound(replace(smoB, draft.submissionId(), first, "rescan.json", rescan()));
        for (ApiClient other : List.of(signedInAs(CM), vaPoc())) {
            assertThat(replace(other, draft.submissionId(), first, "rescan.json", rescan()).status()).isEqualTo(403);
        }
        assertNotFound(replace(draft.smo(), draft.submissionId(), MISSING, "rescan.json", rescan()));
        assertNotFound(replace(draft.smo(), draft.submissionId(), otherFile, "rescan.json", rescan()));
        assertNotFound(replace(draft.smo(), otherSubmission, otherFile, "rescan.json", rescan()));

        assertThat(ApiClient.json(draft.smo().get(filesOf(draft.submissionId())))).as("nothing was kept").hasSize(1);
        assertThat(status(draft.smo(), draft.submissionId(), first).get("supersededBy").isNull()).isTrue();
        assertThat(entries("FILE_UPLOADED", draft.systemId())).as("a 404 is not audited").hasSize(1);
    }

    @Test
    void aVaSubmissionThatTakesNoFilesTakesNoRescan() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        arrange.submissionState(draft.submissionId(), "IN_REVIEW");

        assertConflict(replace(draft.smo(), draft.submissionId(), first, "rescan.json", rescan()), "invalid-transition");

        assertThat(refusals(draft.systemId())).containsExactly("state-not-editable");
        assertThat(entries("FILE_UPLOADED", draft.systemId()).get(0).get("detail").get("replaced_file_id").asString())
                .as("a refused rescan names the file it was to replace").isEqualTo(first);
        assertThat(state(draft)).containsEntry("dc|CVE-2027-0003|" + PKG_C, "MEDIUM/1/-");
    }

    @Test
    void onlyAParsedReportFileThatNoRescanHasReplacedCanBeReplaced() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        String evidence = settledEvidence(draft, DEPENDENCY_CHECK, "memo.pdf", Samples.pdf()).get("id").asString();
        String rejected = settled(draft, DEPENDENCY_CHECK, "page.html", Samples.html()).get("id").asString();
        String scanning = uploaded(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, "later.xml",
                Samples.dependencyCheckXml()).get("id").asString();
        String rescan = replaced(draft, first, "rescan.json", rescan()).get("id").asString();

        for (String file : List.of(evidence, rejected, scanning, rescan)) {
            assertConflict(replace(draft.smo(), draft.submissionId(), file, "again.json", rescan()), "file-not-replaceable");
        }
        pass();
        pass();
        assertConflict(replace(draft.smo(), draft.submissionId(), first, "again.json", rescan()), "file-not-replaceable");

        assertThat(refusals(draft.systemId())).containsExactly("not-replaceable", "not-replaceable", "not-replaceable",
                "not-replaceable", "not-replaceable");
    }

    @Test
    void aFileHasOneRescanOnItsWayAtATime() {
        Draft draft = draft();
        String first = firstScanOf(draft);
        replaced(draft, first, "rescan.json", rescan());

        assertConflict(replace(draft.smo(), draft.submissionId(), first, "rescan-2.json", rescan()), "replace-in-progress");

        assertThat(refusals(draft.systemId())).containsExactly("replace-in-progress");
        long rescans = 0;
        for (JsonNode file : ApiClient.json(draft.smo().get(filesOf(draft.submissionId())))) {
            rescans += file.get("replacesId").isNull() ? 0 : 1;
        }
        assertThat(rescans).as("the second was not kept").isEqualTo(1);
    }

    @Test
    void anEmptyRescanIsRefused() {
        Draft draft = draft();
        String first = firstScanOf(draft);

        var response = replace(draft.smo(), draft.submissionId(), first, "rescan.json", new byte[0]);

        assertThat(response.status()).isEqualTo(400);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/file-empty");
    }

    @Test
    void theUploadTheReplacementAndEachRescanProposalAreEntriesOfIdsAndFixedCodes(CapturedOutput output) {
        Draft draft = draft();
        String first = firstScanOf(draft);
        String charlie = findingId(draft, "CVE-2027-0003");
        String smo = userId(draft.smo());

        String rescanId = replacedAndSettled(draft, first, SENTINEL + ".json",
                jsonReport(dependency("pkg:npm/" + SENTINEL + "@1", "/app/" + SENTINEL + ".js", "CVE-2027-0001", "9.8")))
                .get("id").asString();

        JsonNode upload = entries("FILE_UPLOADED", draft.systemId()).get(0);
        assertThat(upload.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(upload.get("actorId").asString()).isEqualTo(smo);
        assertThat(upload.get("detail").get("file_id").asString()).isEqualTo(rescanId);
        assertThat(upload.get("detail").get("replaced_file_id").asString()).isEqualTo(first);
        assertThat(upload.get("detail").propertyNames()).containsExactlyInAnyOrder("file_id", "replaced_file_id");

        JsonNode replacement = entries("FILE_REPLACED", draft.systemId());
        assertThat(replacement).hasSize(1);
        assertThat(replacement.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(replacement.get(0).get("actorId").asString()).as("for the SMO whose upload caused it").isEqualTo(smo);
        assertThat(replacement.get(0).get("actorRole").asString()).isEqualTo("SMO");
        assertThat(replacement.get(0).get("submissionId").asString()).isEqualTo(draft.submissionId());
        assertThat(replacement.get(0).get("detail").get("file_id").asString()).isEqualTo(rescanId);
        assertThat(replacement.get(0).get("detail").get("replaced_file_id").asString()).isEqualTo(first);
        assertThat(replacement.get(0).get("detail").propertyNames()).containsExactlyInAnyOrder("file_id",
                "replaced_file_id");

        JsonNode proposals = entries("STATUS_PROPOSED", draft.systemId());
        assertThat(proposals).as("alpha, bravo and charlie are reported nowhere now: the rescan names another package")
                .hasSize(3);
        List<String> findingIds = new ArrayList<>();
        for (JsonNode entry : proposals) {
            findingIds.add(entry.get("findingId").asString());
            assertThat(entry.get("actorId").asString()).as("the uploader, with proposed_as saying it was automatic")
                    .isEqualTo(smo);
            assertThat(entry.get("actorRole").asString()).isEqualTo("SMO");
            assertThat(entry.get("detail").get("finding_status").asString()).isEqualTo("REMEDIATED");
            assertThat(entry.get("detail").get("proposed_as").asString()).isEqualTo("RESCAN");
            assertThat(entry.get("detail").get("rescan_file_id").asString()).isEqualTo(rescanId);
            assertThat(entry.get("detail").get("replaced_file_id").asString()).isEqualTo(first);
            assertThat(entry.get("detail").propertyNames()).as("file_id is only ever an approval memo here")
                    .containsExactlyInAnyOrder("finding_status", "proposed_as", "rescan_file_id", "replaced_file_id");
        }
        assertThat(findingIds).contains(charlie).doesNotHaveDuplicates();
        assertThat(finding(draft.smo(), draft.submissionId(), "CVE-2027-0003").get("currentProposal").get("evidence")
                .asString()).as("the rescan's name is data in the proposal").contains(SENTINEL);

        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }
}
