package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 70 (ticket 34, ADR 0039 item 5): a VA POC who acted at another step of a VA Submission, or whom the Register names
 * as its System's SMO/Tech Lead or Cyber Manager, is refused its endorsement, and the other VA POC endorses it. Holding
 * the VA POC role assigns nobody to the endorsing step, so such a VA POC still submits and reviews their own Systems.
 */
class EndorseSegregationTest extends EndorseSeam1 {

    private static final String BOARD = "/api/dashboards/quarter-board";

    @Test
    void aVaPocWhoReviewedItLeavesItToTheOtherVaPoc() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        registeredWith(SMO_A, POC_ONE);
        ApiClient reviewer = vaPoc();
        confirmEverything(reviewer, sub);
        passed(reviewer, sub);
        registeredWith(SMO_A, CM);
        assertThat(boardCard(vaPoc()).get("sodConflict").asBoolean()).as("the board marks it for the reviewer").isTrue();
        assertThat(boardCard(otherVaPoc()).get("sodConflict").asBoolean()).isFalse();

        assertSodConflict(endorse(vaPoc(), sub));

        assertThat(submission(draft.smo(), sub).get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        JsonNode refused = entries("SUBMISSION_ENDORSED", draft.systemId()).get(0);
        assertThat(refused.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("detail").get("reason").asString()).isEqualTo("sod-conflict");

        ApiClient other = otherVaPoc();
        JsonNode answered = endorsed(other, sub);
        assertThat(actedAt(answered, "ENDORSE")).containsExactly(userId(other));
        assertThat(actedAt(answered, "REVIEW")).containsExactly(userId(vaPoc()));
    }

    @Test
    void aVaPocTheRegisterNamesAsItsSmoOrCyberManagerLeavesItToTheOtherVaPoc() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();

        registeredWith(POC_ONE, CM);
        assertThat(boardCard(vaPoc()).get("sodConflict").asBoolean()).as("assigned as its SMO/Tech Lead").isTrue();
        assertSodConflict(endorse(vaPoc(), sub));
        registeredWith(SMO_A, POC_ONE);
        assertThat(boardCard(vaPoc()).get("sodConflict").asBoolean()).as("assigned as its Cyber Manager").isTrue();
        assertSodConflict(endorse(vaPoc(), sub));

        assertThat(entries("SUBMISSION_ENDORSED", draft.systemId())).hasSize(2);
        assertThat(endorsed(otherVaPoc(), sub).get("state").asString()).isEqualTo(ENDORSED);
    }

    @Test
    void aVaPocWhomTheRegisterNamesAsAnSmoStillStartsTheirSystem() {
        registered(List.of(listed("SYS-F1", "File one", POC_ONE, CM, "Q3")), List.of());
        String system = idOf("SYS-F1");

        var response = start(vaPoc(), system);

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(ApiClient.json(response).get("state").asString()).isEqualTo("DRAFT");
    }

    /** SYS-F1 with this SMO/Tech Lead and Cyber Manager, and SYS-F2 as before: a VA POC applies the Register again. */
    private void registeredWith(String smo, String manager) {
        registered(List.of(listed("SYS-F1", "File one", smo, manager, "Q3"),
                listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3")), List.of());
    }

    /** SYS-F1's card in the Awaiting Endorsement column of this VA POC's Quarter board. */
    private static JsonNode boardCard(ApiClient poc) {
        var response = poc.get(BOARD);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        for (JsonNode column : ApiClient.json(response).get("columns")) {
            if (column.get("state").asString().equals(AWAITING_ENDORSEMENT)) {
                for (JsonNode card : column.get("systems")) {
                    if (card.get("systemId").asString().equals("SYS-F1")) {
                        return card;
                    }
                }
            }
        }
        throw new AssertionError("SYS-F1 is not Awaiting Endorsement on the board");
    }
}
