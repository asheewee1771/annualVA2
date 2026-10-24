package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 64, first tab: a VA POC opens {@code GET /api/dashboards/quarter-board} on the Quarter running today and sees every
 * System of that Quarter, whoever its SMO/Tech Lead and Cyber Manager are, in one column per state. It is the Cyber
 * Manager's board read for the endorse scope (ADR 0034 item 1).
 */
class VaPocBoardTest extends CyberManagerSeam1 {

    @Test
    void aVaPocSeesEverySystemOfTheQuarterWhoeverReviewsIt() {
        registered(listed("SYS-P1", "Mine to review", SMO_A, CM, "Q3"),
                listed("SYS-P2", "Someone else's", SMO_B, CM_OTHER, "Q3"),
                listed("SYS-P3", "Also started", SMO_B, CM_OTHER, "Q3"), listed("SYS-P4", "Next Quarter", SMO_A, CM, "Q4"));
        ApiClient smo = signedInAs(SMO_B);
        List<String> findings = enteredFindings(smo, started(smo, "SYS-P3"), 2);
        findings.forEach(finding -> proposed(smo, finding));
        arrange.findingReview(findings.get(0), "CONFIRMED");

        JsonNode board = board(vaPoc(), "");

        assertThat(board.get("cycle").asInt()).isEqualTo(2027);
        assertThat(board.get("quarter").asInt()).isEqualTo(3);
        assertThat(systemIds(column(board, "NOT_STARTED"))).containsExactly("SYS-P1", "SYS-P2");
        JsonNode draft = column(board, "DRAFT");
        assertThat(systemIds(draft)).containsExactly("SYS-P3");
        assertThat(draft.get(0).get("confirmed").asInt()).isEqualTo(1);
        assertThat(draft.get(0).get("awaitingReview").asInt()).isEqualTo(1);
        assertThat(draft.get(0).get("sodConflict").asBoolean()).as("the VA POC neither acted on it nor is assigned to it").isFalse();
        assertThat(board.toString()).as("another Quarter").doesNotContain("SYS-P4");
        assertThat(systemIds(column(board(vaPoc(), "?quarter=4"), "NOT_STARTED")))
                .containsExactly("SYS-P4");
    }

    @Test
    void aVaPocWhoAlsoReviewsASystemSeesTheVaPocsBoardWhichHoldsTheirOwn() {
        registered(listed("SYS-P5", "Reviewed by a VA POC", SMO_A, POC_ONE, "Q3"),
                listed("SYS-P6", "Reviewed by another", SMO_B, CM, "Q3"));

        JsonNode board = board(vaPoc(), "");

        assertThat(systemIds(column(board, "NOT_STARTED"))).containsExactly("SYS-P5", "SYS-P6");
    }
}
