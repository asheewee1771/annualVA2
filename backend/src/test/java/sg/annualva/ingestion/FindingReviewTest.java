package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 50 (ticket 28): the Cyber Manager assigned to a System confirms each proposed Finding Status of its VA Submission
 * In Review, or marks it Disputed with a comment. The review is of the current proposal and records the reviewing step.
 */
@ExtendWith(OutputCaptureExtension.class)
class FindingReviewTest extends ReviewSeam1 {

    @Test
    void theCyberManagerConfirmsTheCurrentProposalAndTheReviewingStepIsRecorded() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        String finding = findingIds(cm, sub).getFirst();
        String proposal = currentProposalId(cm, sub, finding);
        clock.advance(Duration.ofMinutes(3));

        var response = reviewFinding(cm, finding, review(CONFIRMED, null, proposal));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode review = ApiClient.json(response);
        assertThat(review.get("decision").asString()).isEqualTo(CONFIRMED);
        assertThat(review.get("comment").isNull()).isTrue();
        assertThat(review.get("proposalId").asString()).isEqualTo(proposal);
        assertThat(review.get("reviewedBy").asString()).isEqualTo(userId(cm));
        assertThat(review.get("reviewedAt").asString()).isEqualTo(clock.instant().toString());
        JsonNode listed = findingById(draft.smo(), sub, finding);
        assertThat(listed.get("reviewState").asString()).isEqualTo(CONFIRMED);
        assertThat(listed.get("latestReview")).as("as the list shows it").isEqualTo(review);
        JsonNode acted = submission(draft.smo(), sub).get("acted");
        assertThat(acted).hasSize(2);
        assertThat(acted.get(1).get("step").asString()).isEqualTo("REVIEW");
        assertThat(acted.get(1).get("userId").asString()).isEqualTo(userId(cm));
    }

    @Test
    void aDisputeCarriesItsCommentCleanedAndTheSmoSeesIt() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        String finding = findingIds(cm, sub).getFirst();

        JsonNode review = reviewed(cm, sub, finding, DISPUTED, "  The package is still \u202Ein use.\u0007 ");

        assertThat(review.get("decision").asString()).isEqualTo(DISPUTED);
        assertThat(review.get("comment").asString()).as("cleaned like every text a person types")
                .isEqualTo("The package is still in use.");
        JsonNode listed = findingById(draft.smo(), sub, finding);
        assertThat(listed.get("reviewState").asString()).isEqualTo(DISPUTED);
        assertThat(listed.get("latestReview").get("comment").asString()).isEqualTo("The package is still in use.");
        JsonNode counts = readiness(draft.smo(), sub);
        assertThat(counts.get("disputed").asInt()).isEqualTo(1);
    }

    @Test
    void aLaterReviewOfTheSameProposalReplacesTheDecisionAndTheReviewingStepIsRecordedOnce() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        String finding = findingIds(cm, sub).getFirst();
        reviewed(cm, sub, finding, DISPUTED, "Not convinced.");

        JsonNode again = reviewed(cm, sub, finding, CONFIRMED, "Checked the evidence again.");

        JsonNode listed = findingById(cm, sub, finding);
        assertThat(listed.get("reviewState").asString()).isEqualTo(CONFIRMED);
        assertThat(listed.get("latestReview")).isEqualTo(again);
        assertThat(submission(draft.smo(), sub).get("acted")).as("SUBMIT and REVIEW, once each").hasSize(2);
    }

    @Test
    void theFieldsAreCheckedInOrderAndTheFirstWrongOneIsNamed() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        String finding = findingIds(cm, sub).getFirst();
        String proposal = currentProposalId(cm, sub, finding);

        assertInvalidField(reviewFinding(cm, finding, review(null, "x", proposal)), "decision");
        assertInvalidField(reviewFinding(cm, finding, review("PENDING", "x", proposal)), "decision");
        assertInvalidField(reviewFinding(cm, finding, review(DISPUTED, null, proposal)), "comment");
        assertInvalidField(reviewFinding(cm, finding, review(DISPUTED, " \u200B\t", proposal)), "comment");
        assertInvalidField(reviewFinding(cm, finding, review(CONFIRMED, "x".repeat(4001), proposal)), "comment");
        assertInvalidField(reviewFinding(cm, finding, review(CONFIRMED, null, null)), "proposalId");
        assertInvalidField(reviewFinding(cm, finding, review(CONFIRMED, null, "not-an-id")), "proposalId");
        assertThat(reviewFinding(cm, finding, review(DISPUTED, "y".repeat(4000), proposal)).status())
                .as("exactly the limit").isEqualTo(201);
        assertThat(findingById(cm, sub, finding).get("latestReview").get("comment").asString()).hasSize(4000);
    }

    @Test
    void aReviewOfAProposalThatIsNoLongerCurrentIsRefusedAndAudited() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        String finding = findingIds(cm, sub).getFirst();
        String seen = currentProposalId(cm, sub, finding);
        reviewed(cm, sub, finding, DISPUTED, "Show the fix.");
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");
        proposed(draft.smo(), finding, remediated("Upgraded to 2.0."));
        submitted(draft.smo(), sub);

        var stale = reviewFinding(cm, finding, review(CONFIRMED, null, seen));

        assertConflict(stale, "proposal-changed");
        JsonNode listed = findingById(cm, sub, finding);
        assertThat(listed.get("reviewState").asString()).as("the new proposal awaits review").isEqualTo("PENDING");
        assertThat(listed.get("latestReview").get("proposalId").asString())
                .as("the Dispute was of the earlier proposal").isEqualTo(seen);
        assertThat(listed.get("latestReview").get("proposalId").asString())
                .isNotEqualTo(listed.get("currentProposal").get("id").asString());
        JsonNode refused = entries("FINDING_REVIEWED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("findingId").asString()).isEqualTo(finding);
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("proposal-changed");
    }

    @Test
    void aConfirmationHoldsOnlyWhileItsProposalIsCurrent() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        reviewed(cm, sub, ids.get(0), CONFIRMED, null);
        reviewed(cm, sub, ids.get(1), CONFIRMED, null);
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");

        proposed(draft.smo(), ids.get(1), remediated("Fixed after all."));

        assertThat(findingById(cm, sub, ids.get(0)).get("reviewState").asString()).as("unchanged, still confirmed")
                .isEqualTo(CONFIRMED);
        assertThat(findingById(cm, sub, ids.get(1)).get("reviewState").asString()).isEqualTo("PENDING");
        JsonNode counts = readiness(draft.smo(), sub);
        assertThat(counts.get("awaitingReview").asInt()).isEqualTo(1);
    }

    @Test
    void onlyAVaSubmissionInReviewIsReviewedAndTheRefusalIsAudited() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        String finding = findingIds(cm, sub).getFirst();
        String proposal = currentProposalId(cm, sub, finding);

        assertConflict(reviewFinding(cm, finding, review(CONFIRMED, null, proposal)), "invalid-transition");
        submitted(draft.smo(), sub);
        arrange.submissionState(sub, "AWAITING_ENDORSEMENT");
        assertConflict(reviewFinding(cm, finding, review(CONFIRMED, null, proposal)), "invalid-transition");

        assertThat(findingById(cm, sub, finding).get("reviewState").asString()).isEqualTo("PENDING");
        JsonNode entries = entries("FINDING_REVIEWED", draft.systemId());
        assertThat(entries).hasSize(2);
        for (JsonNode entry : entries) {
            assertThat(entry.get("outcome").asString()).isEqualTo("REFUSED");
            assertThat(entry.get("detail").get("reason").asString()).isEqualTo("state-not-reviewable");
        }
        assertThat(submission(draft.smo(), sub).get("acted")).as("no reviewing step recorded").hasSize(1);
    }

    @Test
    void onlyTheCyberManagerOfTheSystemReviewsItsFindings() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        String finding = findingIds(draft.smo(), sub).getFirst();
        String body = review(CONFIRMED, null, currentProposalId(draft.smo(), sub, finding));
        String auditor = "finding.review.auditor@ci.annualva.test";
        ApiClient auditorBrowser = signedInAs(auditor);
        arrange.grant("AUDITOR", auditor, null, "GRANT", "2027-12-31T00:00:00Z");

        assertThat(reviewFinding(draft.smo(), finding, body).status()).as("the SMO/Tech Lead").isEqualTo(403);
        assertThat(reviewFinding(vaPoc(), finding, body).status()).as("a VA POC").isEqualTo(403);
        assertThat(reviewFinding(auditorBrowser, finding, body).status()).as("an Auditor").isEqualTo(403);
        assertThat(reviewFinding(signedInAs("finding.review.nobody@ci.annualva.test"), finding, body).status())
                .as("a person with no role").isEqualTo(403);
        var anonymous = reviewFinding(anotherBrowser().withCsrf(), finding, body);
        assertThat(anonymous.status()).as("no session").isEqualTo(401);
        assertThat(ApiClient.json(anonymous).get("type").asString())
                .isEqualTo("https://annualva/problems/unauthenticated");
        assertNotFound(reviewFinding(signedInAs(CM_OTHER), finding, body));
        assertNotFound(reviewFinding(signedInAs(CM), UUID.randomUUID().toString(), body));

        JsonNode listed = findingById(draft.smo(), sub, finding);
        assertThat(listed.get("reviewState").asString()).as("nothing kept").isEqualTo("PENDING");
        assertThat(listed.get("latestReview").isNull()).isTrue();
        assertThat(submission(draft.smo(), sub).get("acted")).as("no reviewing step recorded").hasSize(1);
    }

    @Test
    void aReviewIsAuditedWithItsDecisionAndNothingTheCyberManagerWrote(CapturedOutput output) {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        String finding = findingIds(cm, sub).getFirst();
        String proposal = currentProposalId(cm, sub, finding);

        reviewed(cm, sub, finding, DISPUTED, "Wrong package " + SENTINEL);
        List<String> bodies = List.of(
                reviewFinding(cm, finding, review(DISPUTED, SENTINEL + "x".repeat(4001), proposal)).body(),
                reviewFinding(cm, finding, review(SENTINEL, "x", proposal)).body(),
                reviewFinding(cm, finding, review(CONFIRMED, SENTINEL, "id-" + SENTINEL)).body(),
                reviewFinding(cm, finding, review(CONFIRMED, SENTINEL, UUID.randomUUID().toString())).body(),
                reviewFinding(signedInAs(CM_OTHER), finding, review(CONFIRMED, SENTINEL, proposal)).body());

        JsonNode entries = entries("FINDING_REVIEWED", draft.systemId());
        JsonNode success = entries.get(1);
        assertThat(success.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(success.get("actorId").asString()).isEqualTo(userId(cm));
        assertThat(success.get("submissionId").asString()).isEqualTo(sub);
        assertThat(success.get("findingId").asString()).isEqualTo(finding);
        assertThat(success.get("detail").propertyNames()).containsExactly("decision");
        assertThat(success.get("detail").get("decision").asString()).isEqualTo(DISPUTED);
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).contains("finding-reviewed");
        assertThat(String.join("\n", bodies)).as("error bodies").doesNotContain(SENTINEL);
        assertThat(findingById(draft.smo(), sub, finding).toString()).as("the data itself").contains(SENTINEL);
    }
}
