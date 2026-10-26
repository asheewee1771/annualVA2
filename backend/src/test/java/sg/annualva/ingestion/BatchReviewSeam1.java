package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 29 share (stories 51 and 52): a VA Submission of SYS-F1 In Review whose Dependency-Check report
 * was read into four Findings, three of them Bulk-set in one batch (Remediation Planned) and the fourth proposed alone,
 * beside a Secure Configuration Finding entered by hand and Burp Suite not applicable; and the calls the Cyber Manager's
 * screen makes to list the batches and review one.
 */
abstract class BatchReviewSeam1 extends ReviewSeam1 {

    static final String PLAN = "Upgrade alpha, bravo and charlie in the next release.";

    /**
     * The VA Submission In Review with its batch.
     *
     * @param members the Findings Bulk-set in the batch, in id order
     * @param alone the Findings proposed one at a time (the fourth parsed one and the one entered by hand)
     */
    protected record Batched(Draft draft, String batchId, List<String> members, List<String> alone) {

        String submissionId() {
            return draft.submissionId();
        }
    }

    protected Batched batchInReview() {
        Draft draft = draft();
        String sub = draft.submissionId();
        parsedDc(draft, "dc.json", jsonReportOf("{\"name\":\"throwaway-project\",\"reportDate\":\"" + SCANNED + "\"}",
                dependency(PKG_A, "/app/alpha.js", "CVE-2027-0001", "9.8"),
                dependency(PKG_B, "/app/bravo.js", "CVE-2027-0002", "7.5"),
                dependency(PKG_C, "/app/charlie.js", "CVE-2027-0003", "5.0"),
                dependency(PKG_D, "/app/delta.js", "CVE-2027-0004", "3.1")));
        modeSet(draft.smo(), sub, SECURE_CONFIGURATION, mode(MANUAL, "The scanner exports PDF only."));
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, "Password policy too weak", "SC-PWD-1",
                "password-min-length", "HIGH"));
        modeSet(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, "The System has no web interface."));
        JsonNode page = findings(draft.smo(), sub);
        List<String> members = new ArrayList<>();
        for (String cve : List.of("CVE-2027-0001", "CVE-2027-0002", "CVE-2027-0003")) {
            members.add(byIdentifier(page, cve).get("id").asString());
        }
        String batchId = bulkSet(draft, members, remediationPlanned(PLAN, LATER));
        List<String> alone = new ArrayList<>(findingIds(draft.smo(), sub));
        alone.removeAll(members);
        proposeEvery(draft);
        submitted(draft.smo(), sub);
        return new Batched(draft, batchId, members.stream().sorted().toList(), alone);
    }

    /** Bulk-sets the proposal on these Findings as their SMO/Tech Lead and expects one batch (201); its id. */
    protected static String bulkSet(Draft draft, List<String> findingIds, String proposal) {
        var response = draft.smo().postJson(SUBMISSIONS + "/" + draft.submissionId() + "/findings/bulk-proposals",
                "{\"findingIds\":" + findingIds.stream().map(ProposalSeam1::quoted).collect(Collectors.joining(",", "[", "]"))
                + "," + proposal.substring(1));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response).get("batchId").asString();
    }

    protected static String batchesOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/batches";
    }

    protected static String batchReviewsOf(String batchId) {
        return "/api/batches/" + batchId + "/reviews";
    }

    /** The batches of the VA Submission as this reader lists them (200). */
    protected static JsonNode batches(ApiClient client, String submissionId) {
        var response = client.get(batchesOf(submissionId));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("cache-control")).contains("no-store");
        return ApiClient.json(response);
    }

    protected static ApiClient.Response reviewBatch(ApiClient client, String batchId, String json) {
        return client.postJson(batchReviewsOf(batchId), json);
    }

    /** Reviews the batch and expects it kept (201); the batch review as answered. */
    protected static JsonNode batchReviewed(ApiClient client, String batchId, String decision, @Nullable String comment) {
        var response = reviewBatch(client, batchId, review(decision, comment, null));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.header("cache-control")).contains("no-store");
        return ApiClient.json(response);
    }

    /** The texts of a JSON array, in order. */
}
