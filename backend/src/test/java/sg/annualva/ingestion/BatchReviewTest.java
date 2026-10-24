package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 51 (ticket 29): {@code POST /api/batches/{id}/reviews} confirms or Disputes, with one comment, every member of a
 * Bulk-set batch whose current proposal is the batch's and still awaits review. Members changed since (proposed again, or
 * reviewed on their own) are left out and listed. It is one batch review in the audit log, with an entry per Finding as
 * well, and it runs the checks of a single review: ownership, segregation of duties and the state.
 */
@ExtendWith(OutputCaptureExtension.class)
class BatchReviewTest extends BatchReviewSeam1 {

    @Autowired
    private JdbcClient application;

    @Test
    void aBatchReviewIsHistoryThatTheApplicationsConnectionCannotChange() {
        Batched batched = batchInReview();
        ApiClient cm = signedInAs(CM);
        batchReviewed(cm, batched.batchId(), CONFIRMED, null);
        String before = batches(cm, batched.submissionId()).toString();

        for (String sql : new String[] {"UPDATE batch_review SET reviewed_at = now()", "DELETE FROM batch_review",
                "TRUNCATE batch_review CASCADE"}) {
            assertThatThrownBy(() -> application.sql(sql).update()).as(sql)
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        }

        assertThat(batches(cm, batched.submissionId()).toString()).isEqualTo(before);
    }

    @Test
    void confirmingABatchConfirmsEveryMemberAwaitingReviewAsOneAuditedBatchReview() {
        Batched batched = batchInReview();
        String sub = batched.submissionId();
        ApiClient cm = signedInAs(CM);
        clock.advance(Duration.ofMinutes(5));

        JsonNode review = batchReviewed(cm, batched.batchId(), CONFIRMED, null);

        assertThat(review.get("batchId").asString()).isEqualTo(batched.batchId());
        assertThat(review.get("decision").asString()).isEqualTo(CONFIRMED);
        assertThat(review.get("comment").isNull()).isTrue();
        assertThat(review.get("reviewedBy").asString()).isEqualTo(userId(cm));
        assertThat(review.get("reviewedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(texts(review.get("reviewed"))).containsExactlyElementsOf(batched.members());
        assertThat(review.get("leftOut")).isEmpty();
        for (String member : batched.members()) {
            JsonNode listed = findingById(batched.draft().smo(), sub, member);
            assertThat(listed.get("reviewState").asString()).isEqualTo(CONFIRMED);
            assertThat(listed.get("latestReview").get("proposalId").asString())
                    .isEqualTo(listed.get("currentProposal").get("id").asString());
            assertThat(listed.get("latestReview").get("reviewedAt").asString()).isEqualTo(clock.instant().toString());
        }
        for (String alone : batched.alone()) {
            assertThat(findingById(cm, sub, alone).get("reviewState").asString()).as("not in the batch")
                    .isEqualTo("PENDING");
        }
        JsonNode batch = batches(cm, sub).get(0);
        assertThat(batch.get("confirmed").asInt()).isEqualTo(3);
        assertThat(batch.get("awaitingReview").asInt()).isZero();
        JsonNode acted = submission(batched.draft().smo(), sub).get("acted");
        assertThat(acted).hasSize(2);
        assertThat(acted.get(1).get("step").asString()).isEqualTo("REVIEW");

        JsonNode entries = entries("BATCH_REVIEWED", batched.draft().systemId());
        assertThat(entries).as("one batch review").hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(cm));
        assertThat(entry.get("submissionId").asString()).isEqualTo(sub);
        assertThat(entry.get("detail").get("batch_id").asString()).isEqualTo(batched.batchId());
        assertThat(entry.get("detail").get("count").asInt()).isEqualTo(3);
        assertThat(entry.get("detail").get("decision").asString()).isEqualTo(CONFIRMED);
        JsonNode perFinding = entries("FINDING_REVIEWED", batched.draft().systemId());
        assertThat(perFinding).as("and one per Finding, so a Finding's trail shows it").hasSize(3);
        for (JsonNode each : perFinding) {
            assertThat(batched.members()).contains(each.get("findingId").asString());
            assertThat(each.get("detail").get("batch_id").asString()).isEqualTo(batched.batchId());
            assertThat(each.get("detail").get("decision").asString()).isEqualTo(CONFIRMED);
        }
    }

    @Test
    void disputingABatchNeedsOneCommentWhichEveryMemberCarries() {
        Batched batched = batchInReview();
        String sub = batched.submissionId();
        ApiClient cm = signedInAs(CM);

        assertInvalidField(reviewBatch(cm, batched.batchId(), review(DISPUTED, " ​", null)), "comment");
        JsonNode review = batchReviewed(cm, batched.batchId(), DISPUTED, "  Charlie is still ‮in the image. ");

        assertThat(review.get("comment").asString()).isEqualTo("Charlie is still in the image.");
        for (String member : batched.members()) {
            JsonNode listed = findingById(cm, sub, member);
            assertThat(listed.get("reviewState").asString()).isEqualTo(DISPUTED);
            assertThat(listed.get("latestReview").get("comment").asString()).isEqualTo("Charlie is still in the image.");
        }
        assertThat(readiness(batched.draft().smo(), sub).get("disputed").asInt()).isEqualTo(3);
        assertThat(batches(cm, sub).get(0).get("disputed").asInt()).isEqualTo(3);
    }

    @Test
    void theFieldsAreCheckedInOrderAndWhatABatchReviewDoesNotUseIsRefused() {
        Batched batched = batchInReview();
        ApiClient cm = signedInAs(CM);
        String batch = batched.batchId();

        assertInvalidField(reviewBatch(cm, batch, review(null, "x", null)), "decision");
        assertInvalidField(reviewBatch(cm, batch, review("PENDING", "x", null)), "decision");
        assertInvalidField(reviewBatch(cm, batch, review(DISPUTED, null, null)), "comment");
        assertInvalidField(reviewBatch(cm, batch, review(CONFIRMED, "x".repeat(4001), null)), "comment");
        assertInvalidField(reviewBatch(cm, batch, review(CONFIRMED, null, UUID.randomUUID().toString())), "proposalId");
        assertInvalidField(reviewBatch(cm, batch, justificationReview(CONFIRMED, null, 0)), "justificationVersion");

        assertThat(batches(cm, batched.submissionId()).get(0).get("awaitingReview").asInt()).as("nothing kept")
                .isEqualTo(3);
        assertThat(entries("BATCH_REVIEWED", batched.draft().systemId())).as("a 400 is not audited").isEmpty();
    }

    @Test
    void membersChangedSinceTheBatchAreLeftOutAndListed() {
        Batched batched = batchInReview();
        String sub = batched.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> members = batched.members();
        reviewed(cm, sub, members.get(0), DISPUTED, "Show the alpha upgrade.");
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");
        proposed(batched.draft().smo(), members.get(1), remediated("Upgraded bravo alone."));
        arrange.submissionState(sub, "IN_REVIEW");

        JsonNode review = batchReviewed(cm, batched.batchId(), CONFIRMED, "Plan agreed.");

        assertThat(texts(review.get("reviewed"))).containsExactly(members.get(2));
        JsonNode leftOut = review.get("leftOut");
        assertThat(leftOut).hasSize(2);
        assertThat(leftOut.get(0).get("findingId").asString()).isEqualTo(members.get(0));
        assertThat(leftOut.get(0).get("reason").asString()).isEqualTo("REVIEWED");
        assertThat(leftOut.get(1).get("findingId").asString()).isEqualTo(members.get(1));
        assertThat(leftOut.get(1).get("reason").asString()).isEqualTo("PROPOSAL_CHANGED");
        assertThat(findingById(cm, sub, members.get(0)).get("reviewState").asString()).as("as it was reviewed alone")
                .isEqualTo(DISPUTED);
        assertThat(findingById(cm, sub, members.get(1)).get("reviewState").asString()).as("its own proposal awaits")
                .isEqualTo("PENDING");
        assertThat(findingById(cm, sub, members.get(2)).get("reviewState").asString()).isEqualTo(CONFIRMED);
        JsonNode entry = entries("BATCH_REVIEWED", batched.draft().systemId()).get(0);
        assertThat(entry.get("detail").get("count").asInt()).as("only what was reviewed").isEqualTo(1);
    }

    @Test
    void aBatchWithNothingLeftToReviewIsRefusedAndAudited() {
        Batched batched = batchInReview();
        ApiClient cm = signedInAs(CM);
        batchReviewed(cm, batched.batchId(), CONFIRMED, null);

        var again = reviewBatch(cm, batched.batchId(), review(DISPUTED, "Changed my mind.", null));

        assertConflict(again, "nothing-to-review");
        for (String member : batched.members()) {
            assertThat(findingById(cm, batched.submissionId(), member).get("reviewState").asString())
                    .isEqualTo(CONFIRMED);
        }
        JsonNode refused = entries("BATCH_REVIEWED", batched.draft().systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("batch_id").asString()).isEqualTo(batched.batchId());
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("nothing-to-review");
    }

    @Test
    void onlyTheCyberManagerOfTheSystemReviewsABatchAndOnlyWhileItIsInReview() {
        Batched batched = batchInReview();
        String sub = batched.submissionId();
        String body = review(CONFIRMED, null, null);

        assertThat(reviewBatch(batched.draft().smo(), batched.batchId(), body).status()).as("the SMO/Tech Lead")
                .isEqualTo(403);
        assertThat(reviewBatch(vaPoc(), batched.batchId(), body).status()).as("a VA POC").isEqualTo(403);
        assertNotFound(reviewBatch(signedInAs(CM_OTHER), batched.batchId(), body));
        assertNotFound(reviewBatch(signedInAs(CM), UUID.randomUUID().toString(), body));
        arrange.submissionState(sub, "AWAITING_ENDORSEMENT");
        assertConflict(reviewBatch(signedInAs(CM), batched.batchId(), body), "invalid-transition");

        assertThat(batches(batched.draft().smo(), sub).get(0).get("awaitingReview").asInt()).isEqualTo(3);
        JsonNode entries = entries("BATCH_REVIEWED", batched.draft().systemId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entries.get(0).get("detail").get("reason").asString()).isEqualTo("state-not-reviewable");
        assertThat(entries.get(0).get("detail").get("batch_id").asString()).isEqualTo(batched.batchId());
        assertThat(submission(batched.draft().smo(), sub).get("acted")).as("no reviewing step recorded").hasSize(1);
    }

    @Test
    void aCyberManagerAssignedToAnotherStepMayNotReviewTheBatch() {
        Batched batched = batchInReview();
        ApiClient cm = signedInAs(CM);
        arrange.grant("SMO", CM, batched.draft().systemId());

        assertSodConflict(reviewBatch(cm, batched.batchId(), review(CONFIRMED, null, null)));

        assertThat(batches(cm, batched.submissionId()).get(0).get("awaitingReview").asInt()).isEqualTo(3);
        JsonNode refused = entries("BATCH_REVIEWED", batched.draft().systemId()).get(0);
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("sod-conflict");
    }

    @Test
    void aBatchReviewWritesNothingTheCyberManagerWroteToALogAnAuditEntryOrAnErrorBody(CapturedOutput output) {
        Batched batched = batchInReview();
        ApiClient cm = signedInAs(CM);

        batchReviewed(cm, batched.batchId(), DISPUTED, "Not convinced " + SENTINEL);
        List<String> bodies = List.of(
                reviewBatch(cm, batched.batchId(), review(DISPUTED, SENTINEL + "x".repeat(4001), null)).body(),
                reviewBatch(cm, batched.batchId(), review(SENTINEL, "x", null)).body(),
                reviewBatch(cm, batched.batchId(), review(CONFIRMED, SENTINEL, null)).body(),
                reviewBatch(signedInAs(CM_OTHER), batched.batchId(), review(CONFIRMED, SENTINEL, null)).body());

        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(SENTINEL);
        assertThat(output.getAll()).as("the log").doesNotContain(SENTINEL).contains("batch-reviewed");
        assertThat(String.join("\n", bodies)).as("error bodies").doesNotContain(SENTINEL);
        assertThat(findingById(cm, batched.submissionId(), batched.members().getFirst()).toString())
                .as("the data itself").contains(SENTINEL);
    }
}
