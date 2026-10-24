package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 34 share (stories 65, 67 and 70): a VA Submission of SYS-F1 passed to endorsement by its Cyber
 * Manager, and the endorse call a VA POC's screen makes.
 */
abstract class EndorseSeam1 extends ReturnSeam1 {

    static final String ENDORSED = "ENDORSED";

    /** The Draft of SYS-F1 made ready, submitted, every Finding and justification confirmed and passed on. */
    protected Draft awaitingEndorsement() {
        Draft draft = inReview();
        ApiClient cm = signedInAs(CM);
        confirmEverything(cm, draft.submissionId());
        passed(cm, draft.submissionId());
        return draft;
    }

    protected static String endorseOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/endorse";
    }

    protected static ApiClient.Response endorse(ApiClient client, String submissionId) {
        return client.postJson(endorseOf(submissionId), "{}");
    }

    /** Who acted at this step of the VA Submission as read, by user id. */
    protected static List<String> actedAt(JsonNode submission, String step) {
        List<String> who = new ArrayList<>();
        submission.get("acted").forEach(acted -> {
            if (acted.get("step").asString().equals(step)) {
                who.add(acted.get("userId").asString());
            }
        });
        return who;
    }

    /** Endorses the VA Submission and expects it Endorsed (200); as answered. */
    protected static JsonNode endorsed(ApiClient client, String submissionId) {
        var response = endorse(client, submissionId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }
}
