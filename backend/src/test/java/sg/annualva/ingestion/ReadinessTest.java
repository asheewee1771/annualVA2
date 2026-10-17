package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 38 (ticket 20): {@code GET /api/submissions/{id}/readiness} gives the counts the progress bar shows: every Finding,
 * those with a proposed status, those awaiting the Cyber Manager's review, the Disputed ones, and the Carried Over and
 * Target Date Missed ones (always none in Release 1, which has neither). Anyone who may see the System reads it; a read by
 * anyone but the System's own SMO/Tech Lead is audited with ids only, as a read of the VA Submission is.
 */
class ReadinessTest extends ProposalSeam1 {

    private static String readinessOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/readiness";
    }

    private static JsonNode readiness(ApiClient client, String submissionId) {
        var response = client.get(readinessOf(submissionId));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("cache-control")).contains("no-store");
        return ApiClient.json(response);
    }

    private static void assertCounts(JsonNode readiness, int total, int proposed, int unproposed, int awaitingReview,
            int disputed) {
        assertThat(readiness.get("total").asInt()).as("total").isEqualTo(total);
        assertThat(readiness.get("proposed").asInt()).as("proposed").isEqualTo(proposed);
        assertThat(readiness.get("unproposed").asInt()).as("unproposed").isEqualTo(unproposed);
        assertThat(readiness.get("awaitingReview").asInt()).as("awaitingReview").isEqualTo(awaitingReview);
        assertThat(readiness.has("pending")).as("no field is called pending").isFalse();
        assertThat(readiness.get("disputed").asInt()).as("disputed").isEqualTo(disputed);
        assertThat(readiness.get("carriedOver").asInt()).as("no carry-over in Release 1").isZero();
        assertThat(readiness.get("targetDateMissed").asInt()).as("no Target Date Missed in Release 1").isZero();
    }

    @Test
    void aVaSubmissionWithNoFindingsYetCountsNothing() {
        Draft draft = draft();

        assertCounts(readiness(draft.smo(), draft.submissionId()), 0, 0, 0, 0, 0);
    }

    @Test
    void theCountsFollowTheProposalsAndTheirReview() {
        Draft draft = draftWithFindings();
        assertCounts(readiness(draft.smo(), draft.submissionId()), 7, 0, 7, 0, 0);

        String pending = findingId(draft, "CVE-2021-44228");
        String confirmed = findingId(draft, "CVE-2021-45046");
        String disputed = findingId(draft, "CVE-2022-42889");
        for (String id : List.of(pending, confirmed, disputed)) {
            proposed(draft.smo(), id, remediated("Patched."));
        }
        assertCounts(readiness(draft.smo(), draft.submissionId()), 7, 3, 4, 3, 0);

        arrange.findingReview(confirmed, "CONFIRMED");
        arrange.findingReview(disputed, "DISPUTED");
        assertCounts(readiness(draft.smo(), draft.submissionId()), 7, 3, 4, 1, 1);

        proposed(draft.smo(), disputed, remediated("Patched again."));
        assertCounts(readiness(draft.smo(), draft.submissionId()), 7, 3, 4, 2, 0);
        proposed(draft.smo(), pending, falsePositive("A second proposal for the same Finding."));
        assertCounts(readiness(draft.smo(), draft.submissionId()), 7, 3, 4, 2, 0);
    }

    @Test
    void onlyThisVaSubmissionsFindingsAreCounted() {
        Draft draft = draftWithFindings();
        ApiClient smoB = signedInAs(SMO_B);
        Draft other = new Draft(smoB, idOf("SYS-F2"), started(smoB, idOf("SYS-F2")).get("id").asString());
        settledDc(other, "dc.xml", sg.annualva.harness.Samples.dependencyCheckSampleXml());
        proposed(smoB, findingId(other, "CVE-2021-44228"), remediated("Patched."));

        assertCounts(readiness(draft.smo(), draft.submissionId()), 7, 0, 7, 0, 0);
        assertCounts(readiness(smoB, other.submissionId()), 7, 1, 6, 1, 0);
    }

    @Test
    void anyoneWhoMaySeeTheSystemReadsItAndAnyoneElseGetsNotFound() {
        Draft draft = draftWithFindings();
        proposed(draft.smo(), findingId(draft, "CVE-2021-44228"), remediated("Patched."));

        for (ApiClient reader : List.of(signedInAs(CM), vaPoc())) {
            assertCounts(readiness(reader, draft.submissionId()), 7, 1, 6, 1, 0);
        }
        var stranger = signedInAs(SMO_B).get(readinessOf(draft.submissionId()));
        var missing = draft.smo().get(readinessOf("00000000-0000-4000-8000-000000000000"));
        assertNotFound(stranger);
        assertNotFound(missing);
        assertThat(ApiClient.json(stranger).get("detail")).isEqualTo(ApiClient.json(missing).get("detail"));
        assertNotFound(signedInAs(CM_OTHER).get(readinessOf(draft.submissionId())));
        assertThat(anotherBrowser().get(readinessOf(draft.submissionId())).status()).as("not signed in").isEqualTo(401);
    }

    @Test
    void aReadByTheCyberManagerOrAVaPocIsAuditedWithIdsOnlyAndTheOwnersReadIsNot() {
        Draft draft = draftWithFindings();

        readiness(draft.smo(), draft.submissionId());
        readiness(signedInAs(CM), draft.submissionId());
        readiness(vaPoc(), draft.submissionId());
        signedInAs(SMO_B).get(readinessOf(draft.submissionId()));

        JsonNode entries = entries("READINESS_VIEWED", draft.systemId());
        assertThat(entries).as("the Cyber Manager and the VA POC, not the owner, not the 404").hasSize(2);
        assertThat(List.of(entries.get(0).get("actorRole").asString(), entries.get(1).get("actorRole").asString()))
                .containsExactlyInAnyOrder("CYBER_MANAGER", "VA_POC");
        for (JsonNode entry : entries) {
            assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(entry.get("submissionId").asString()).isEqualTo(draft.submissionId());
            assertThat(entry.get("detail").propertyNames()).isEmpty();
        }
    }
}
