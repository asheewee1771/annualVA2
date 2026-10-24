package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 91 for the in-app list (ticket 36): no notification ever carries Finding detail. A sentinel is put into every place
 * a person or a report can write text that a notification might copy (a Finding entered by hand, a Dispute and a return
 * comment), the Findings of a parsed report stand for the rest, and the workflow is driven through submit, return and the
 * Overdue sweep; then nobody's notifications hold any of it.
 */
class NotificationContentTest extends NotificationSeam1 {

    private static final String SENTINEL = "CVE-9999-SENTINEL";

    @Test
    void noNotificationCarriesAFindingAVulnerabilityOrAComment() {
        Draft draft = readyDraft();
        String sub = draft.submissionId();
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, SENTINEL + " weak cipher", SENTINEL + "-ID",
                SENTINEL + "-rule", "HIGH"));
        proposeEvery(draft);
        submitted(draft.smo(), sub);
        ApiClient cm = signedInAs(CM);
        List<String> detail = findingDetail(cm, sub);
        reviewed(cm, sub, findingIds(cm, sub).get(0), DISPUTED, SENTINEL + " in the dispute");
        returned(cm, sub, SENTINEL + " in the return");
        today("2027-08-30");
        arrange.overdueSweep();

        List<String> bodies = new ArrayList<>();
        for (ApiClient reader : List.of(signedInAs(SMO_A), signedInAs(CM), vaPoc(), otherVaPoc())) {
            var response = reader.get(NOTIFICATIONS);
            assertThat(response.status()).isEqualTo(200);
            bodies.add(response.body());
        }

        assertThat(actions(signedInAs(SMO_A))).contains("REWORK", "SUBMIT_OVERDUE");
        assertThat(detail).as("the report's Findings and the one entered by hand").hasSizeGreaterThan(4)
                .anyMatch(text -> text.contains(SENTINEL));
        for (String body : bodies) {
            assertThat(body).doesNotContain("SENTINEL");
            detail.forEach(text -> assertThat(body).doesNotContain(text));
        }
    }

    /** Every identifying text of every Finding of the VA Submission: its key, identifier, package or rule and title. */
    private static List<String> findingDetail(ApiClient reader, String submissionId) {
        List<String> detail = new ArrayList<>();
        for (JsonNode finding : findings(reader, submissionId).get("items")) {
            for (String field : List.of("identityKey", "identifier", "packageOrRule", "title")) {
                JsonNode value = finding.get(field);
                if (value != null && !value.isNull()) {
                    detail.add(value.asString());
                }
            }
        }
        return detail;
    }
}
