package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Stories 60 and 61 (ticket 31): an SMO/Tech Lead whose VA Submission was Returned for Rework works through a queue of
 * the Findings that need them, Disputed first, then new Critical/High, then new Medium/Low/Informational, each with the
 * Cyber Manager's latest Dispute comment and the current proposal.
 */
class ReworkQueueTest extends ReturnSeam1 {

    private static final String NEW_CRITICAL_HIGH = "NEW_CRITICAL_HIGH";
    private static final String NEW_MEDIUM_LOW = "NEW_MEDIUM_LOW";

    private static String reworkQueueOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/rework-queue";
    }

    /** The rework queue as this reader reads it (200). */
    private static JsonNode reworkQueue(ApiClient client, String submissionId) {
        var response = client.get(reworkQueueOf(submissionId));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** The identifiers of the queue's items, in order. */
    private static List<String> identifiers(JsonNode queue) {
        List<String> identifiers = new ArrayList<>();
        queue.get("items").forEach(item -> identifiers.add(item.get("finding").get("identifier").asString()));
        return identifiers;
    }

    /** The stage of each of the queue's items, in order. */
    private static List<String> stagesOf(JsonNode queue) {
        List<String> stages = new ArrayList<>();
        queue.get("items").forEach(item -> stages.add(item.get("stage").asString()));
        return stages;
    }

    /** A Finding of the Secure Configuration Scan Report entered by hand with this identifier and severity. */
    private static void enteredSc(Draft draft, String identifier, String severity) {
        entered(draft.smo(), draft.submissionId(),
                manualFinding(SECURE_CONFIGURATION, "Throwaway check " + identifier, identifier, "rule-" + identifier,
                        severity));
    }

    @Test
    void theQueueListsWhatIsDisputedThenNewCriticalAndHighThenNewMediumLowAndInformational() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        reviewed(cm, sub, ids.get(0), CONFIRMED, null);
        reviewed(cm, sub, ids.get(1), DISPUTED, "Show the password policy.");
        returned(cm, sub, null);
        enteredSc(draft, "SC-LOW-1", "LOW");
        enteredSc(draft, "SC-CRIT-1", "CRITICAL");
        enteredSc(draft, "SC-MED-1", "MEDIUM");
        enteredSc(draft, "SC-INFO-1", "INFORMATIONAL");
        enteredSc(draft, "SC-HIGH-1", "HIGH");
        enteredSc(draft, "SC-HIGH-2", "HIGH");
        proposed(draft.smo(), findingId(draft, "SC-HIGH-2"), falsePositive("Not reachable."));

        JsonNode queue = reworkQueue(draft.smo(), sub);

        assertThat(identifiers(queue)).as("the confirmed Finding and one already proposed again are not in it")
                .containsExactly("SC-PWD-1", "SC-CRIT-1", "SC-HIGH-1", "SC-MED-1", "SC-LOW-1", "SC-INFO-1");
        assertThat(stagesOf(queue)).containsExactly(DISPUTED, NEW_CRITICAL_HIGH, NEW_CRITICAL_HIGH, NEW_MEDIUM_LOW,
                NEW_MEDIUM_LOW, NEW_MEDIUM_LOW);
        assertThat(queue.get("total").asInt()).isEqualTo(6);
        JsonNode stages = queue.get("stages");
        assertThat(stages).hasSize(3);
        assertThat(stages.get(0).get("stage").asString()).isEqualTo(DISPUTED);
        assertThat(stages.get(0).get("count").asInt()).isEqualTo(1);
        assertThat(stages.get(1).get("stage").asString()).isEqualTo(NEW_CRITICAL_HIGH);
        assertThat(stages.get(1).get("count").asInt()).isEqualTo(2);
        assertThat(stages.get(2).get("stage").asString()).isEqualTo(NEW_MEDIUM_LOW);
        assertThat(stages.get(2).get("count").asInt()).isEqualTo(3);
    }

    @Test
    void eachFindingComesWithTheLatestDisputeCommentAndTheCurrentProposal() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        JsonNode dispute = reviewed(cm, sub, ids.get(1), DISPUTED, "Show the password policy.");
        reviewed(cm, sub, ids.get(0), DISPUTED, "Name the fixed version.");
        returned(cm, sub, "Two to look at again.");
        enteredSc(draft, "SC-MED-1", "MEDIUM");

        JsonNode items = reworkQueue(draft.smo(), sub).get("items");

        assertThat(identifiers(reworkQueue(draft.smo(), sub))).as("within a stage, the most severe first")
                .containsExactly("CVE-2027-0001", "SC-PWD-1", "SC-MED-1");
        JsonNode disputed = items.get(1);
        assertThat(disputed.get("dispute").get("id").asString()).isEqualTo(dispute.get("id").asString());
        assertThat(disputed.get("dispute").get("comment").asString()).isEqualTo("Show the password policy.");
        assertThat(disputed.get("dispute").get("reviewedBy").asString()).isEqualTo(userId(cm));
        JsonNode proposal = disputed.get("finding").get("currentProposal");
        assertThat(proposal.get("status").asString()).isEqualTo("FALSE_POSITIVE");
        assertThat(proposal.get("justification").asString()).isEqualTo("Not reachable in this System.");
        assertThat(disputed.get("finding").get("reviewState").asString()).isEqualTo(DISPUTED);
        JsonNode fresh = items.get(2);
        assertThat(fresh.get("dispute").isNull()).as("never Disputed").isTrue();
        assertThat(fresh.get("finding").get("currentProposal").isNull()).isTrue();
    }

    @Test
    void aNewProposalOnADisputedFindingResetsItsReviewAndTheResubmitSendsItBackToReview() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        reviewed(cm, sub, ids.get(0), CONFIRMED, null);
        reviewed(cm, sub, ids.get(1), DISPUTED, "Show the password policy.");
        returned(cm, sub, null);

        proposed(draft.smo(), ids.get(1), remediated("The policy now asks for 14 characters."));

        JsonNode finding = findingById(draft.smo(), sub, ids.get(1));
        assertThat(finding.get("reviewState").asString()).isEqualTo("PENDING");
        assertThat(finding.get("latestReview").get("decision").asString()).as("the Dispute is still read")
                .isEqualTo(DISPUTED);
        JsonNode queue = reworkQueue(draft.smo(), sub);
        assertThat(queue.get("items")).as("nothing left to rework").isEmpty();
        assertThat(queue.get("total").asInt()).isZero();
        assertThat(queue.get("stages").get(0).get("count").asInt()).isZero();

        assertThat(submitted(draft.smo(), sub).get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(findingById(cm, sub, ids.get(0)).get("reviewState").asString()).as("still confirmed")
                .isEqualTo(CONFIRMED);
    }

    @Test
    void onlyTheSystemsSmoReadsItsQueue() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");
        returned(cm, sub, null);

        assertThat(cm.get(reworkQueueOf(sub)).status()).as("a Cyber Manager").isEqualTo(403);
        assertThat(vaPoc().get(reworkQueueOf(sub)).status()).as("a VA POC").isEqualTo(403);
        assertNotFound(signedInAs(SMO_B).get(reworkQueueOf(sub)));
        assertNotFound(draft.smo().get(reworkQueueOf(UUID.randomUUID().toString())));
        for (String bad : List.of("?size=0", "?size=501", "?page=-1")) {
            assertThat(draft.smo().get(reworkQueueOf(sub) + bad).status()).as(bad).isEqualTo(400);
        }
    }

    @Test
    void aPageOfTheQueueKeepsItsOrderAndTotal() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix.");
        returned(cm, sub, null);
        enteredSc(draft, "SC-LOW-1", "LOW");
        enteredSc(draft, "SC-HIGH-1", "HIGH");

        var response = draft.smo().get(reworkQueueOf(sub) + "?page=1&size=1");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode page = ApiClient.json(response);
        assertThat(identifiers(page)).containsExactly("SC-HIGH-1");
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(1);
        assertThat(page.get("total").asInt()).isEqualTo(3);
    }

    @Test
    void everyReadIsAuditedWithTheCountAndNeverTheText() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).getFirst(), DISPUTED, "Show the fix " + SENTINEL + ".");
        returned(cm, sub, null);

        String body = draft.smo().get(reworkQueueOf(sub)).body();

        assertThat(body).as("the Dispute comment is data for the screen").contains(SENTINEL);
        JsonNode audited = entries("REWORK_QUEUE_VIEWED", draft.systemId());
        assertThat(audited).hasSize(1);
        assertThat(audited.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(audited.get(0).get("actorId").asString()).isEqualTo(userId(draft.smo()));
        assertThat(audited.get(0).get("submissionId").asString()).isEqualTo(sub);
        assertThat(audited.get(0).get("detail").get("count").asInt()).isEqualTo(1);
        assertThat(otherVaPoc().get("/api/audit-entries?size=200&system=" + draft.systemId()).body())
                .doesNotContain(SENTINEL);
    }
}
