package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 30 share (stories 55 and 57): the calls the Cyber Manager's review screen makes to return a VA
 * Submission for rework or pass it to endorsement, and the reads that show what a return kept.
 */
abstract class ReturnSeam1 extends ReviewSeam1 {

    static final String RETURNED_FOR_REWORK = "RETURNED_FOR_REWORK";
    static final String AWAITING_ENDORSEMENT = "AWAITING_ENDORSEMENT";

    /**
     * SYS-F1's VA Submission Returned for Rework by its Cyber Manager with one Finding Disputed: one mail is queued, to
     * {@link #SMO_A}, and nothing else is due.
     */
    protected Draft returnedForRework() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        reviewed(cm, sub, findingIds(cm, sub).get(0), DISPUTED, "Show the upgrade.");
        returned(cm, sub, "Two items need another look.");
        return draft;
    }

    protected static String returnOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/return";
    }

    protected static String passOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/pass-to-endorsement";
    }

    protected static String returnsOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/returns";
    }

    /** The body of a return: a comment, or none when null. */
    protected static String returnBody(@Nullable String comment) {
        return comment == null ? "{}" : "{\"comment\":" + quoted(comment) + "}";
    }

    protected static ApiClient.Response returnForRework(ApiClient client, String submissionId, @Nullable String comment) {
        return client.postJson(returnOf(submissionId), returnBody(comment));
    }

    /** Returns the VA Submission for rework and expects it to go back to the SMO/Tech Lead (200); as answered. */
    protected static JsonNode returned(ApiClient client, String submissionId, @Nullable String comment) {
        var response = returnForRework(client, submissionId, comment);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    protected static ApiClient.Response passToEndorsement(ApiClient client, String submissionId) {
        return client.postJson(passOf(submissionId), "{}");
    }

    /** Passes the VA Submission to endorsement and expects it to go to the VA POCs (200); as answered. */
    protected static JsonNode passed(ApiClient client, String submissionId) {
        var response = passToEndorsement(client, submissionId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** The returns of the VA Submission as this reader lists them, the latest first. */
    protected static JsonNode returns(ApiClient client, String submissionId) {
        var response = client.get(returnsOf(submissionId));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** SYS-x's row on the VA POC's Cycle matrix for the Cycle running today. */
    protected JsonNode matrixRow(String systemId) {
        var response = vaPoc().get("/api/dashboards/cycle-matrix");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        for (JsonNode system : ApiClient.json(response).get("systems")) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system;
            }
        }
        throw new AssertionError("no " + systemId + " on the matrix");
    }

    /** Confirms every Finding and the Burp Suite justification of a VA Submission In Review, as its Cyber Manager. */
    protected static void confirmEverything(ApiClient cm, String submissionId) {
        for (String finding : findingIds(cm, submissionId)) {
            reviewed(cm, submissionId, finding, CONFIRMED, null);
        }
        reportReviewed(cm, submissionId, BURP_SUITE, CONFIRMED, null);
    }
}
