package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 85 (ticket 48): a Cyber Manager reads the audit history of the VA Submissions they reviewed, on Systems still
 * assigned to them, through the same audit log API and filters as a VA POC. Any other VA Submission answers 404.
 */
class CmAuditHistoryTest extends ReviewSeam1 {

    private static final String AUDIT = "/api/audit-entries?size=200";

    private static ApiClient.Response history(ApiClient client, String query) {
        return client.get(AUDIT + (query.isEmpty() ? "" : "&" + query));
    }

    /** Every entry of the answer, which the test expects to be a 200 on one page. */
    private static List<JsonNode> items(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = ApiClient.json(response);
        List<JsonNode> items = new ArrayList<>();
        body.get("items").forEach(items::add);
        assertThat(items).hasSize(body.get("total").asInt());
        return items;
    }

    private static List<String> actionsOf(List<JsonNode> entries) {
        return entries.stream().map(entry -> entry.get("action").asString()).toList();
    }

    /** A Cyber Manager who reviewed the In Review VA Submission of SYS-F1: they confirmed its first Finding. */
    private Draft reviewedBy(ApiClient cm) {
        Draft draft = inReview();
        reviewed(cm, draft.submissionId(), findingIds(cm, draft.submissionId()).getFirst(), CONFIRMED, null);
        return draft;
    }

    @Test
    void aCyberManagerReadsTheWholeHistoryOfAVaSubmissionTheyReviewed() {
        ApiClient cm = signedInAs(CM);
        Draft draft = reviewedBy(cm);

        List<JsonNode> entries = items(history(cm, "submission=" + draft.submissionId()));

        assertThat(entries).allSatisfy(entry -> assertThat(entry.get("submissionId").asString())
                .isEqualTo(draft.submissionId()));
        assertThat(actionsOf(entries)).as("what the SMO/Tech Lead did and what the Cyber Manager did")
                .contains("SUBMISSION_STARTED", "SUBMISSION_SUBMITTED", "FINDING_REVIEWED");
        assertThat(entries).as("the same entries a VA POC reads")
                .hasSameSizeAs(items(history(otherVaPoc(), "submission=" + draft.submissionId())));
    }

    @Test
    void aVaSubmissionTheCyberManagerHasNotReviewedAnswersNotFound() {
        Draft draft = inReview();
        String query = "submission=" + draft.submissionId();
        ApiClient assigned = signedInAs(CM);
        ApiClient otherSystems = signedInAs(CM_OTHER);

        assertNotFound(history(assigned, query));
        assertNotFound(history(otherSystems, query));
        assertNotFound(history(assigned, "submission=" + java.util.UUID.randomUUID()));
        assertThat(history(assigned, query).body()).as("exactly like one that does not exist")
                .doesNotContain(draft.submissionId());
    }

    @Test
    void aDisputeOfAJustificationIsAReviewToo() {
        Draft draft = inReview();
        ApiClient cm = signedInAs(CM);
        reportReviewed(cm, draft.submissionId(), BURP_SUITE, DISPUTED, "Burp Suite still applies.");

        List<JsonNode> entries = items(history(cm, "submission=" + draft.submissionId() + "&action=REPORT_REVIEWED"));

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().get("detail").get("decision").asString()).isEqualTo(DISPUTED);
    }

    @Test
    void onceTheSystemIsReassignedTheFormerReviewerNoLongerReadsItsHistory() {
        ApiClient cm = signedInAs(CM);
        Draft draft = reviewedBy(cm);
        String query = "submission=" + draft.submissionId();
        assertThat(items(history(cm, query))).isNotEmpty();

        // Release 2's mid-cycle transfer: Release 1 blocks it while the VA Submission is in flight (ADR 0049).
        registeredMidCycle(listed("SYS-F1", "File one", SMO_A, CM_OTHER, "Q3"), listed("SYS-F2", "File two", SMO_B, CM, "Q3"));

        assertNotFound(history(cm, query));
        assertThat(items(history(cm, ""))).as("nothing else to read").isEmpty();
        assertNotFound(history(signedInAs(CM_OTHER), query));
    }

    @Test
    void withoutAVaSubmissionTheFiltersNarrowOnlyWhatTheCyberManagerReviewed() {
        ApiClient cm = signedInAs(CM);
        Draft draft = reviewedBy(cm);
        String cmId = userId(cm);
        // SYS-F2 becomes the Cyber Manager's too, and its SMO/Tech Lead starts its VA Submission: it has a history, on a
        // System assigned to them, that they have not reviewed.
        registered(List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"),
                listed("SYS-F2", "File two", SMO_B, CM, "Q3")), List.of());
        String unreviewed = started(signedInAs(SMO_B), idOf("SYS-F2")).get("id").asString();
        assertThat(items(history(otherVaPoc(), "submission=" + unreviewed))).isNotEmpty();

        List<JsonNode> everything = items(history(cm, ""));
        assertThat(everything).isNotEmpty().allSatisfy(entry -> assertThat(entry.get("submissionId").asString())
                .as("no entry about a VA Submission not reviewed, nor about a System alone")
                .isEqualTo(draft.submissionId()));
        assertNotFound(history(cm, "submission=" + unreviewed));

        assertThat(actionsOf(items(history(cm, "action=FINDING_REVIEWED")))).containsExactly("FINDING_REVIEWED");
        assertThat(items(history(cm, "actor=" + cmId))).isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.get("actorId").asString()).isEqualTo(cmId));
        assertThat(items(history(cm, "system=" + draft.systemId()))).hasSameSizeAs(everything);
        assertThat(items(history(cm, "system=" + idOf("SYS-F2")))).as("another System narrows to nothing").isEmpty();
    }
}
