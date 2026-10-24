package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 64 with story 41: the submit command itself is what the VA POC's Cycle matrix shows as the submitted milestone,
 * on the business date it happened, and the review becomes the milestone the VA Submission waits on.
 */
class SubmitMilestoneTest extends SubmitSeam1 {

    @Test
    void aSubmitShowsOnTheCycleMatrixAsTheSubmittedMilestone() {
        Draft draft = readyDraft();
        submitted(draft.smo(), draft.submissionId());

        var response = vaPoc().get("/api/dashboards/cycle-matrix");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode row = null;
        for (JsonNode system : ApiClient.json(response).get("systems")) {
            if (system.get("systemId").asString().equals("SYS-F1")) {
                row = system;
            }
        }
        assertThat(row).as("SYS-F1 on the matrix").isNotNull();
        assertThat(row.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(row.get("submitted").get("doneOn").asString()).as("the default clock's business date")
                .isEqualTo("2027-08-02");
        assertThat(row.get("submitted").get("current").asBoolean()).isFalse();
        assertThat(row.get("reviewed").get("current").asBoolean()).isTrue();
        assertThat(row.get("reviewed").get("doneOn").isNull()).isTrue();
    }
}
