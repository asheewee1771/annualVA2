package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Story 94 (ticket 36): an SMO/Tech Lead is told when the System Register moves their System to another Quarter, or
 * transfers it to or from them. Q3 2027's Submission Window ends on 15 August and Q4's on 14 November.
 */
class RegisterNotificationTest extends NotificationSeam1 {

    @Test
    void aMoveToAnotherQuarterTellsTheSmoWithTheNewQuartersDueDate() {
        registered(listed("SYS-N1", "Notice one", SMO_A, CM, "Q3"));

        registered(listed("SYS-N1", "Notice one", SMO_A, CM, "Q4"));

        JsonNode item = only(signedInAs(SMO_A), "SYSTEM_MOVED");
        assertThat(item.get("systemCode").asString()).isEqualTo("SYS-N1");
        assertThat(item.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(item.get("submissionId").isNull()).isTrue();
        assertThat(item.get("dueOn").asString()).isEqualTo("2027-11-14");
        assertThat(item.get("link").asString()).isEqualTo("/smo");
        assertThat(notifications(signedInAs(CM))).as("the Cyber Manager is not told").isEmpty();
    }

    @Test
    void aTransferTellsTheNewSmoAndTheFormerOne() {
        // Not Started: a transfer of a System with in-flight work is blocked in Release 1 (ticket 43, ADR 0049).
        registered(listed("SYS-N1", "Notice one", SMO_A, CM, "Q3"), listed("SYS-N2", "Notice two", SMO_A, CM, "Q3"));

        registered(listed("SYS-N1", "Notice one", SMO_B, CM, "Q3"), listed("SYS-N2", "Notice two", SMO_A, CM, "Q3"));

        JsonNode incoming = only(signedInAs(SMO_B), "SYSTEM_TRANSFERRED_TO_YOU");
        assertThat(incoming.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(incoming.get("submissionId").isNull()).isTrue();
        assertThat(incoming.get("dueOn").asString()).isEqualTo("2027-08-15");
        assertThat(incoming.get("link").asString()).isEqualTo("/smo");
        JsonNode outgoing = only(signedInAs(SMO_A), "SYSTEM_TRANSFERRED_FROM_YOU");
        assertThat(outgoing.get("systemCode").asString()).isEqualTo("SYS-N1");
        assertThat(outgoing.get("dueOn").isNull()).as("nothing is due from them any more").isTrue();
        assertThat(outgoing.get("link").asString()).isEqualTo("/smo");
        assertThat(actions(signedInAs(SMO_B))).as("a transfer is not a move").containsExactly("SYSTEM_TRANSFERRED_TO_YOU");
    }
}
