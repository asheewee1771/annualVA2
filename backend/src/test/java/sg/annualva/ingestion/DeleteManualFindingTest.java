package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 60 (story 23): {@code DELETE /api/findings/{findingId}} lets the SMO/Tech Lead delete a Manually Entered Finding
 * entered by mistake while the VA Submission is in Draft or Returned for Rework. Proposals and their history are never
 * deleted, so a Finding that has had a proposal stays (it is given a Finding Status instead), and a parsed Finding goes
 * only with the file that reports it. Once the Scan Report holds nothing, its mode can change again.
 */
@ExtendWith(OutputCaptureExtension.class)
class DeleteManualFindingTest extends DeleteSeam1 {

    private static final String LOG4SHELL = manualFinding(DEPENDENCY_CHECK, "Remote code execution in Log4j",
            "CVE-2021-44228", "log4j-core 2.14.1", "CRITICAL");

    @Test
    void aFindingEnteredByMistakeIsDeletedAndTheScanReportsModeCanChangeAgain(CapturedOutput output) {
        Draft draft = manualDraft();
        String sub = draft.submissionId();
        String id = entered(draft.smo(), sub, manualFinding(DEPENDENCY_CHECK, SENTINEL, "CVE-" + SENTINEL, SENTINEL,
                "HIGH")).get("id").asString();
        String upload = mode(UPLOAD, null);
        assertConflict(setMode(draft.smo(), sub, DEPENDENCY_CHECK, upload), "report-not-empty");

        findingDeleted(draft.smo(), id);

        assertThat(findings(draft.smo(), sub).get("total").asInt()).isZero();
        assertNotFound(draft.smo().get(proposalsOf(id)));
        assertNotFound(deleteFinding(draft.smo(), id));
        JsonNode entry = onlyEntry("FINDING_DELETED", "SUCCESS", draft.systemId());
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(draft.smo()));
        assertThat(entry.get("actorRole").asString()).isEqualTo("SMO");
        assertThat(entry.get("submissionId").asString()).isEqualTo(sub);
        assertThat(entry.get("findingId").asString()).isEqualTo(id);
        assertThat(entry.get("detail").get("report_type").asString()).isEqualTo(DEPENDENCY_CHECK);
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).doesNotContain(SENTINEL);

        assertThat(entered(draft.smo(), sub, LOG4SHELL).get("identifier").asString())
                .as("a Finding can be entered again").isEqualTo("CVE-2021-44228");
        findingDeleted(draft.smo(), findingId(draft, "CVE-2021-44228"));
        assertThat(modeSet(draft.smo(), sub, DEPENDENCY_CHECK, upload).get("mode").asString()).isEqualTo(UPLOAD);
    }

    @Test
    void aFindingThatHasHadAProposalKeepsItsHistoryAndIsNotDeleted() {
        Draft draft = manualDraft();
        String id = entered(draft.smo(), draft.submissionId(), LOG4SHELL).get("id").asString();
        proposed(draft.smo(), id, remediated("Upgraded to 2.17.1."));

        assertConflict(deleteFinding(draft.smo(), id), "finding-has-history");

        assertThat(identifiers(draft)).containsExactly("CVE-2021-44228");
        assertThat(ApiClient.json(draft.smo().get(proposalsOf(id)))).hasSize(1);
        assertThat(onlyEntry("FINDING_DELETED", "REFUSED", draft.systemId()).get("detail").get("reason").asString())
                .isEqualTo("has-history");
    }

    @Test
    void aParsedFindingGoesOnlyWithTheFileThatReportsIt() {
        Draft draft = draft();
        settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());
        String parsed = findingId(draft, "CVE-2021-44228");

        assertConflict(deleteFinding(draft.smo(), parsed), "not-manually-entered");

        assertThat(identifiers(draft)).contains("CVE-2021-44228");
        JsonNode refused = onlyEntry("FINDING_DELETED", "REFUSED", draft.systemId());
        assertThat(refused.get("findingId").asString()).isEqualTo(parsed);
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("not-manually-entered");
    }

    @Test
    void onlyTheSmoOfTheSystemDeletesAFindingAndAStrangerOrAMissingOneIsNotFound() {
        Draft draft = manualDraft();
        String id = entered(draft.smo(), draft.submissionId(), LOG4SHELL).get("id").asString();

        assertNotFound(deleteFinding(signedInAs(SMO_B), id));
        assertNotFound(deleteFinding(draft.smo(), UUID.randomUUID().toString()));
        assertThat(deleteFinding(signedInAs(CM), id).status()).isEqualTo(403);
        assertThat(deleteFinding(vaPoc(), id).status()).isEqualTo(403);

        assertThat(identifiers(draft)).containsExactly("CVE-2021-44228");
        assertThat(entries("FINDING_DELETED", draft.systemId())).as("a 404 is not audited").isEmpty();
    }

    @Test
    void aVaSubmissionInReviewKeepsItsFindingsAndTheRefusalIsAudited() {
        Draft draft = inReview();
        JsonNode manual = finding(draft.smo(), draft.submissionId(), "SC-PWD-1");

        assertConflict(deleteFinding(draft.smo(), manual.get("id").asString()), "invalid-transition");

        assertThat(identifiers(draft)).contains("SC-PWD-1");
        JsonNode refused = onlyEntry("FINDING_DELETED", "REFUSED", draft.systemId());
        assertThat(refused.get("findingId").asString()).isEqualTo(manual.get("id").asString());
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("state-not-editable");
    }
}
