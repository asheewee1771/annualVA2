package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 58 (ticket 28): a person may not act at more than one step on the same VA Submission. Whoever acted at another
 * step is refused, and so is whoever the Register assigns to another step of its System, even before they act: the
 * System's SMO/Tech Lead counts as its submitter and its Cyber Manager as its reviewer. Every refusal is 403
 * {@code sod-conflict} and audited, and the Cyber Manager's inbox marks the same VA Submissions.
 */
class ReviewSegregationTest extends ReviewSeam1 {

    /** SYS-F1's Register row after a change that makes {@code smo} its SMO/Tech Lead and {@code cm} its Cyber Manager. */
    private void reassigned(String smo, String cm) {
        registered(List.of(listed("SYS-F1", "File one", smo, cm, "Q3"),
                listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3")), List.of());
    }

    private static boolean sodMarked(ApiClient cm, String systemId) {
        var response = cm.get("/api/dashboards/cm-inbox");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        for (JsonNode system : ApiClient.json(response).get("systems")) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system.get("sodConflict").asBoolean();
            }
        }
        throw new AssertionError("no System " + systemId);
    }

    @Test
    void aCyberManagerWhoSubmittedTheVaSubmissionAsItsSmoMayNotReviewIt() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        String finding = findingIds(draft.smo(), sub).getFirst();
        String proposal = currentProposalId(draft.smo(), sub, finding);
        reassigned(SMO_B, SMO_A);
        ApiClient formerSmo = signedInAs(SMO_A);

        assertThat(sodMarked(formerSmo, "SYS-F1")).as("the inbox marks it").isTrue();
        assertSodConflict(reviewFinding(formerSmo, finding, review(CONFIRMED, null, proposal)));
        assertSodConflict(reviewReport(formerSmo, sub, BURP_SUITE,
                justificationReview(CONFIRMED, null, justificationVersion(formerSmo, sub, BURP_SUITE))));

        assertThat(findingById(formerSmo, sub, finding).get("reviewState").asString()).isEqualTo("PENDING");
        assertThat(report(formerSmo, sub, BURP_SUITE).get("reviewState").asString()).isEqualTo("PENDING");
        assertThat(submission(formerSmo, sub).get("acted")).as("no reviewing step recorded").hasSize(1);
        JsonNode finds = entries("FINDING_REVIEWED", draft.systemId());
        assertThat(finds).hasSize(1);
        assertThat(finds.get(0).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(finds.get(0).get("findingId").asString()).isEqualTo(finding);
        assertThat(finds.get(0).get("detail").get("reason").asString()).isEqualTo("sod-conflict");
        JsonNode reports = entries("REPORT_REVIEWED", draft.systemId());
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).get("detail").get("reason").asString()).isEqualTo("sod-conflict");
    }

    @Test
    void theSystemsAssignedSmoMayNotReviewItEvenBeforeActing() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        String finding = findingIds(draft.smo(), sub).getFirst();
        String proposal = currentProposalId(draft.smo(), sub, finding);
        ApiClient cm = signedInAs(CM);
        assertThat(sodMarked(cm, "SYS-F1")).isFalse();

        arrange.grant("SMO", CM, draft.systemId());

        assertThat(sodMarked(cm, "SYS-F1")).as("the inbox marks it").isTrue();
        assertSodConflict(reviewFinding(cm, finding, review(CONFIRMED, null, proposal)));
        assertThat(findingById(cm, sub, finding).get("reviewState").asString()).isEqualTo("PENDING");
    }

    @Test
    void aCyberManagerWhoReviewedTheVaSubmissionMayNotSubmitItAsItsSmo() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");
        reassigned(CM, CM_OTHER);
        ApiClient formerCm = signedInAs(CM);

        assertSodConflict(submitAccepting(formerCm, sub));

        assertThat(submission(formerCm, sub).get("state").asString()).isEqualTo("RETURNED_FOR_REWORK");
        JsonNode refused = entries("SUBMISSION_SUBMITTED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("sod-conflict");
        assertThat(refused.get("submissionId").asString()).isEqualTo(sub);
    }

    @Test
    void theSystemsAssignedCyberManagerMayNotStartOrSubmitItEvenBeforeActing() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        String other = idOf("SYS-F2");
        arrange.grant("CYBER_MANAGER", SMO_A, draft.systemId());
        arrange.grant("SMO", CM_OTHER, other);

        assertSodConflict(submitAccepting(draft.smo(), sub));
        assertSodConflict(start(signedInAs(CM_OTHER), other));

        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo("DRAFT");
        JsonNode submitRefused = entries("SUBMISSION_SUBMITTED", draft.systemId()).get(0);
        assertThat(submitRefused.get("detail").get("reason").asString()).isEqualTo("sod-conflict");
        JsonNode startRefused = entries("SUBMISSION_STARTED", other).get(0);
        assertThat(startRefused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(startRefused.get("detail").get("reason").asString()).isEqualTo("sod-conflict");
    }

    @Test
    void theCyberManagerAssignedOnlyToReviewReviewsAndIsNotMarked() {
        Draft draft = inReview();
        ApiClient cm = signedInAs(CM);

        assertThat(sodMarked(cm, "SYS-F1")).isFalse();
        reviewed(cm, draft.submissionId(), findingIds(cm, draft.submissionId()).getFirst(), CONFIRMED, null);

        assertThat(sodMarked(cm, "SYS-F1")).as("reviewing is their own step").isFalse();
    }
}
