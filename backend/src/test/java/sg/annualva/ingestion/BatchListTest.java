package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 51 (ticket 29): {@code GET /api/submissions/{id}/batches} lists the Bulk-set batches of a VA Submission with what
 * was proposed in each and how many of its members stand where, and the Findings list narrows to a batch's members, so the
 * review screen groups them under their batch.
 */
class BatchListTest extends BatchReviewSeam1 {

    @Test
    void eachBatchIsListedWithWhatWasProposedAndItsMemberCounts() {
        Batched batched = batchInReview();
        ApiClient cm = signedInAs(CM);

        JsonNode list = batches(cm, batched.submissionId());

        assertThat(list).hasSize(1);
        JsonNode batch = list.get(0);
        assertThat(batch.get("batchId").asString()).isEqualTo(batched.batchId());
        assertThat(batch.get("proposedBy").asString()).isEqualTo(userId(batched.draft().smo()));
        assertThat(batch.get("proposedAt").asString()).isNotBlank();
        JsonNode proposal = batch.get("proposal");
        assertThat(proposal.get("status").asString()).isEqualTo("REMEDIATION_PLANNED");
        assertThat(proposal.get("plan").asString()).isEqualTo(PLAN);
        assertThat(proposal.get("targetDate").asString()).isEqualTo(LATER);
        assertThat(proposal.get("batchId").asString()).isEqualTo(batched.batchId());
        assertThat(batch.get("count").asInt()).isEqualTo(3);
        assertThat(batch.get("awaitingReview").asInt()).isEqualTo(3);
        assertThat(batch.get("confirmed").asInt()).isZero();
        assertThat(batch.get("disputed").asInt()).isZero();
        assertThat(batch.get("changedSince").asInt()).isZero();
    }

    @Test
    void theFindingsListNarrowsToABatchsMembersOrToTheFindingsInNoBatch() {
        Batched batched = batchInReview();
        String sub = batched.submissionId();
        ApiClient cm = signedInAs(CM);

        assertThat(listedIds(cm, sub, "batchId=" + batched.batchId()))
                .containsExactlyInAnyOrderElementsOf(batched.members());
        assertThat(listedIds(cm, sub, "batched=false")).containsExactlyInAnyOrderElementsOf(batched.alone());
        assertThat(listedIds(cm, sub, "batched=true")).containsExactlyInAnyOrderElementsOf(batched.members());
        assertThat(listedIds(cm, sub, "batchId=" + UUID.randomUUID())).isEmpty();
        var counted = cm.get(SUBMISSIONS + "/" + sub + "/findings?batchId=" + batched.batchId());
        assertThat(ApiClient.json(counted).get("total").asInt()).isEqualTo(3);
    }

    @Test
    void aMemberProposedAgainLeavesItsBatchsGroupAndIsCountedAsChangedSince() {
        Batched batched = batchInReview();
        String sub = batched.submissionId();
        String moved = batched.members().get(1);
        arrange.submissionState(sub, "RETURNED_FOR_REWORK");

        proposed(batched.draft().smo(), moved, remediated("Upgraded bravo alone."));

        ApiClient cm = signedInAs(CM);
        assertThat(listedIds(cm, sub, "batchId=" + batched.batchId())).hasSize(2).doesNotContain(moved);
        assertThat(listedIds(cm, sub, "batched=false")).contains(moved);
        JsonNode batch = batches(cm, sub).get(0);
        assertThat(batch.get("count").asInt()).as("its members when it was made").isEqualTo(3);
        assertThat(batch.get("awaitingReview").asInt()).isEqualTo(2);
        assertThat(batch.get("changedSince").asInt()).isEqualTo(1);
    }

    @Test
    void anyoneWhoMaySeeTheSystemListsItsBatchesAndTheReadIsAuditedWithACountOnly() {
        Batched batched = batchInReview();
        String sub = batched.submissionId();

        assertThat(batches(batched.draft().smo(), sub)).hasSize(1);
        assertThat(batches(vaPoc(), sub)).hasSize(1);
        assertNotFound(signedInAs(CM_OTHER).get(batchesOf(sub)));
        assertNotFound(signedInAs(CM).get(batchesOf(UUID.randomUUID().toString())));

        JsonNode entries = entries("BATCHES_VIEWED", batched.draft().systemId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("submissionId").asString()).isEqualTo(sub);
        assertThat(entries.get(0).get("detail").propertyNames()).containsExactly("count");
        assertThat(entries.get(0).get("detail").get("count").asInt()).isEqualTo(1);
        assertThat(otherVaPoc().get("/api/audit-entries?size=200").body()).doesNotContain(PLAN);
    }
}
