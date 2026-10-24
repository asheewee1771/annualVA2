package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 28 share (stories 50 and 58): a VA Submission of SYS-F1 submitted for review (a Dependency-Check
 * Finding read from a file, a Secure Configuration Finding entered by hand, Burp Suite not applicable, every Finding
 * proposed False Positive), and the review calls the Cyber Manager's screen makes. {@link #CM} is SYS-F1's Cyber Manager.
 */
abstract class ReviewSeam1 extends SubmitSeam1 {

    static final String CONFIRMED = "CONFIRMED";
    static final String DISPUTED = "DISPUTED";

    /** The Draft of SYS-F1 made ready and submitted by its SMO/Tech Lead: it is In Review. */
    protected Draft inReview() {
        Draft draft = readyDraft();
        submitted(draft.smo(), draft.submissionId());
        return draft;
    }

    protected static String reviewsOf(String findingId) {
        return "/api/findings/" + findingId + "/reviews";
    }

    protected static String reportReviewOf(String submissionId, String reportType) {
        return SUBMISSIONS + "/" + submissionId + "/reports/" + reportType + "/review";
    }

    /** The body of a review; a null field is left out. */
    protected static String review(@Nullable String decision, @Nullable String comment, @Nullable String proposalId) {
        List<String> fields = new ArrayList<>();
        if (decision != null) {
            fields.add("\"decision\":" + quoted(decision));
        }
        if (comment != null) {
            fields.add("\"comment\":" + quoted(comment));
        }
        if (proposalId != null) {
            fields.add("\"proposalId\":" + quoted(proposalId));
        }
        return "{" + String.join(",", fields) + "}";
    }

    protected static ApiClient.Response reviewFinding(ApiClient client, String findingId, String json) {
        return client.postJson(reviewsOf(findingId), json);
    }

    /** Reviews the Finding's current proposal and expects it kept (201); the review as answered. */
    protected static JsonNode reviewed(ApiClient client, String submissionId, String findingId, String decision,
            @Nullable String comment) {
        var response = reviewFinding(client, findingId,
                review(decision, comment, currentProposalId(client, submissionId, findingId)));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response);
    }

    protected static ApiClient.Response reviewReport(ApiClient client, String submissionId, String reportType,
            String json) {
        return client.postJson(reportReviewOf(submissionId, reportType), json);
    }

    /**
     * The body of a review of a not-applicable justification: the decision, a comment and the version reviewed (as text,
     * as the screen sends it); a null field is left out.
     */
    protected static String justificationReview(@Nullable String decision, @Nullable String comment,
            @Nullable Integer version) {
        String body = review(decision, comment, null);
        if (version == null) {
            return body;
        }
        String field = "\"justificationVersion\":\"" + version + "\"";
        return body.equals("{}") ? "{" + field + "}" : body.substring(0, body.length() - 1) + "," + field + "}";
    }

    /** The version of a Scan Report's justification as this reader sees it now. */
    protected static int justificationVersion(ApiClient client, String submissionId, String reportType) {
        return report(client, submissionId, reportType).get("justificationVersion").asInt();
    }

    /** Reviews the not-applicable justification as now shown, and expects it kept (201); the review as answered. */
    protected static JsonNode reportReviewed(ApiClient client, String submissionId, String reportType, String decision,
            @Nullable String comment) {
        var response = reviewReport(client, submissionId, reportType,
                justificationReview(decision, comment, justificationVersion(client, submissionId, reportType)));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response);
    }

    /** One Finding of the VA Submission as this reader lists it. */
    protected static JsonNode findingById(ApiClient client, String submissionId, String findingId) {
        for (JsonNode finding : findings(client, submissionId).get("items")) {
            if (finding.get("id").asString().equals(findingId)) {
                return finding;
            }
        }
        throw new AssertionError("no Finding " + findingId);
    }

    protected static String currentProposalId(ApiClient client, String submissionId, String findingId) {
        return findingById(client, submissionId, findingId).get("currentProposal").get("id").asString();
    }

    /** The ids of the VA Submission's Findings, the most severe first. */
    protected static List<String> findingIds(ApiClient client, String submissionId) {
        List<String> ids = new ArrayList<>();
        findings(client, submissionId).get("items").forEach(finding -> ids.add(finding.get("id").asString()));
        return ids;
    }

    /** The answer is a 403 {@code sod-conflict} problem. */
    protected static void assertSodConflict(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/sod-conflict");
    }
}
