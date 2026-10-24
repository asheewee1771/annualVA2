package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.submission.SubmissionEvent;
import tools.jackson.databind.JsonNode;

/**
 * Story 55 (ticket 30): the Cyber Manager returns a VA Submission In Review for rework with its Disputed Findings and
 * justifications attached, and what they confirmed stays confirmed.
 */
class ReturnForReworkTest extends ReturnSeam1 {

    @Test
    void theCyberManagerReturnsItWithTheDisputedFindingAttachedAndTheConfirmedOneStaysConfirmed() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        JsonNode dispute = reviewed(cm, sub, ids.get(0), DISPUTED, "Show the upgrade.");
        reviewed(cm, sub, ids.get(1), CONFIRMED, null);
        clock.advance(Duration.ofMinutes(5));
        events.reset();

        JsonNode answered = returned(cm, sub, "Two items need another look.");

        assertThat(answered.get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(findingById(draft.smo(), sub, ids.get(0)).get("reviewState").asString()).isEqualTo(DISPUTED);
        assertThat(findingById(draft.smo(), sub, ids.get(1)).get("reviewState").asString())
                .as("confirmed stays confirmed").isEqualTo(CONFIRMED);

        JsonNode kept = returns(draft.smo(), sub);
        assertThat(kept).hasSize(1);
        JsonNode first = kept.get(0);
        assertThat(first.get("step").asString()).isEqualTo("REVIEW");
        assertThat(first.get("comment").asString()).isEqualTo("Two items need another look.");
        assertThat(first.get("returnedBy").asString()).isEqualTo(userId(cm));
        assertThat(first.get("returnedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(first.get("disputedFindings")).hasSize(1);
        assertThat(first.get("disputedFindings").get(0).get("findingId").asString()).isEqualTo(ids.get(0));
        assertThat(first.get("disputedFindings").get(0).get("reviewId").asString())
                .isEqualTo(dispute.get("id").asString());
        assertThat(first.get("disputedReports")).isEmpty();

        assertThat(events.published()).hasSize(1);
        SubmissionEvent event = events.published().getFirst();
        assertThat(event.submissionId()).isEqualTo(UUID.fromString(sub));
        assertThat(event.from().name()).isEqualTo("IN_REVIEW");
        assertThat(event.to().name()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(event.actorId()).isEqualTo(UUID.fromString(userId(cm)));

        JsonNode audited = entries("SUBMISSION_RETURNED", draft.systemId());
        assertThat(audited).hasSize(1);
        assertThat(audited.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get(0).get("submissionId").asString()).isEqualTo(sub);
        assertThat(audited.get(0).get("detail").get("state_from").asString()).isEqualTo("IN_REVIEW");
        assertThat(audited.get(0).get("detail").get("state_to").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(audited.get(0).get("detail").get("count").asInt()).as("what was attached").isEqualTo(1);
    }

    @Test
    void aDisputedJustificationIsAttachedAndTheCommentIsOptional() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        JsonNode dispute = reportReviewed(cm, sub, BURP_SUITE, DISPUTED, "The System has a web console.");

        returned(cm, sub, null);

        JsonNode first = returns(cm, sub).get(0);
        assertThat(first.get("comment").isNull()).isTrue();
        assertThat(first.get("disputedFindings")).isEmpty();
        assertThat(first.get("disputedReports")).hasSize(1);
        assertThat(first.get("disputedReports").get(0).get("reportType").asString()).isEqualTo(BURP_SUITE);
        assertThat(first.get("disputedReports").get(0).get("reviewId").asString())
                .isEqualTo(dispute.get("id").asString());
        assertThat(report(draft.smo(), sub, BURP_SUITE).get("reviewState").asString()).isEqualTo(DISPUTED);
    }

    @Test
    void theCommentIsCleanedAndBounded() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");

        assertInvalidField(returnForRework(cm, sub, "x".repeat(4001)), "comment");
        assertThat(submission(cm, sub).get("state").asString()).as("nothing moved").isEqualTo("IN_REVIEW");
        returned(cm, sub, "  Look again‮ at both.\u0007 ​");

        assertThat(returns(cm, sub).get(0).get("comment").asString()).as("cleaned like every text a person types")
                .isEqualTo("Look again at both.");
    }

    @Test
    void aCommentOfExactlyTheLimitIsKept() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");

        returned(cm, sub, "y".repeat(4000));

        assertThat(returns(cm, sub).get(0).get("comment").asString()).hasSize(4000);
    }

    @Test
    void aCommentWithNothingLeftOnceCleanedIsNone() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");

        returned(cm, sub, " ​\t ");

        assertThat(returns(cm, sub).get(0).get("comment").isNull()).isTrue();
    }

    @Test
    void nothingDisputedIsRefusedAndAudited() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), CONFIRMED, null);

        assertConflict(returnForRework(cm, sub, "Please redo."), "nothing-disputed");

        assertThat(submission(cm, sub).get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(returns(cm, sub)).isEmpty();
        assertThat(events.published()).as("only the submit").hasSize(1);
        JsonNode refused = entries("SUBMISSION_RETURNED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("submissionId").asString()).isEqualTo(sub);
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("nothing-disputed");
    }

    @Test
    void onlyAVaSubmissionInReviewIsReturnedAndOnlyByItsCyberManager() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");

        assertThat(returnForRework(draft.smo(), sub, null).status()).as("not a Cyber Manager").isEqualTo(403);
        assertNotFound(returnForRework(signedInAs(CM_OTHER), sub, null));
        assertNotFound(returnForRework(cm, UUID.randomUUID().toString(), null));
        returned(cm, sub, null);

        assertConflict(returnForRework(cm, sub, null), "invalid-transition");
        JsonNode refused = entries("SUBMISSION_RETURNED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("state-not-reviewable");
        assertThat(returns(cm, sub)).as("returned once").hasSize(1);
    }

    @Test
    void aReturnedVaSubmissionTakesNoMoreReviewsUntilItIsSubmittedAgain() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        reviewed(cm, sub, ids.get(0), DISPUTED, "Show the fix.");
        reviewed(cm, sub, ids.get(1), CONFIRMED, null);
        returned(cm, sub, null);

        assertConflict(reviewFinding(cm, ids.get(1), review(DISPUTED, "Changed my mind.",
                currentProposalId(cm, sub, ids.get(1)))), "invalid-transition");
        proposed(draft.smo(), ids.get(0), remediated("Upgraded to 2.0."));
        submitted(draft.smo(), sub);

        assertThat(findingById(cm, sub, ids.get(0)).get("reviewState").asString()).as("the new proposal")
                .isEqualTo("PENDING");
        assertThat(findingById(cm, sub, ids.get(1)).get("reviewState").asString()).as("still confirmed")
                .isEqualTo(CONFIRMED);
        assertThat(returns(cm, sub).get(0).get("disputedFindings").get(0).get("findingId").asString())
                .as("the return still names what it was about").isEqualTo(ids.get(0));
    }
}
