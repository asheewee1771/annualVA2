package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Stories 18 and 71 (Not Started): an SMO/Tech Lead sees their Systems for the VA Cycle, each in its Quarter with the
 * state (Not Started, since no VA Submission exists yet), the three due dates and the next action; Systems in Quarters
 * Handled outside the app are listed apart with no action.
 */
class SmoInboxTest extends InboxSeam1 {

    private void registerSystems() {
        registered(
                List.of(listed("SYS-I1", "Inbox one", SMO_A, CM, "Q3"),
                        listed("SYS-I2", "Inbox two", SMO_A, CM, "Q4"),
                        listed("SYS-I3", "Inbox three", SMO_B, CM, "Q3"),
                        listed("SYS-I0", "Inbox zero", SMO_A, CM, "Q1")),
                List.of());
    }

    @Test
    void anSmoSeesEachOfTheirSystemsNotStartedWithItsQuarterDueDatesAndTheNextAction() {
        registerSystems();

        JsonNode inbox = inbox(signedInAs(SMO_A));

        assertThat(inbox.get("cycle").asInt()).isEqualTo(2027);
        assertThat(listed(inbox, "systems")).as("by Quarter, then System ID").containsExactly("SYS-I1", "SYS-I2");
        JsonNode q3 = item(inbox, "systems", "SYS-I1");
        assertThat(q3.get("name").asString()).isEqualTo("Inbox one");
        assertThat(q3.get("quarter").asInt()).isEqualTo(3);
        assertThat(q3.get("state").asString()).as("no VA Submission exists yet").isEqualTo("NOT_STARTED");
        assertThat(q3.get("submitDue").asString()).isEqualTo("2027-08-15");
        assertThat(q3.get("reviewDue").asString()).isEqualTo("2027-08-29");
        assertThat(q3.get("endorseDue").asString()).isEqualTo("2027-10-31");
        assertThat(q3.get("nextAction").asString()).isEqualTo("START_SUBMISSION");
        assertThat(q3.get("nextActionDueOn").asString()).isEqualTo("2027-08-15");
        assertThat(q3.get("group").asString()).isEqualTo("NEEDS_YOUR_ACTION");
        assertThat(q3.get("id").asString()).isNotBlank();
    }

    @Test
    void aSystemInALaterQuarterCanBeStartedBeforeItsQuarterBegins() {
        registerSystems();

        JsonNode q4 = item(inbox(signedInAs(SMO_A)), "systems", "SYS-I2");

        assertThat(q4.get("quarter").asInt()).isEqualTo(4);
        assertThat(q4.get("submitDue").asString()).isEqualTo("2027-11-14");
        assertThat(q4.get("reviewDue").asString()).isEqualTo("2027-11-28");
        assertThat(q4.get("endorseDue").asString()).isEqualTo("2028-02-06");
        assertThat(q4.get("nextAction").asString()).isEqualTo("START_SUBMISSION");
        assertThat(q4.get("group").asString()).isEqualTo("NEEDS_YOUR_ACTION");
    }

    @Test
    void systemsInQuartersHandledOutsideTheAppAreListedApartWithNoStateDatesOrAction() {
        registerSystems();

        JsonNode inbox = inbox(signedInAs(SMO_A));

        assertThat(listed(inbox, "handledOutsideApp")).containsExactly("SYS-I0");
        JsonNode outside = item(inbox, "handledOutsideApp", "SYS-I0");
        assertThat(outside.get("name").asString()).isEqualTo("Inbox zero");
        assertThat(outside.get("quarter").asInt()).isEqualTo(1);
        for (String absent : List.of("state", "submitDue", "reviewDue", "endorseDue", "nextAction", "nextActionDueOn", "group")) {
            assertThat(outside.has(absent)).as(absent + " is not offered for a Quarter handled outside the app").isFalse();
        }
    }

    @Test
    void anSmoSeesNothingOfOtherPeoplesSystems() {
        registerSystems();

        JsonNode inbox = inbox(signedInAs(SMO_B));

        assertThat(listed(inbox, "systems")).containsExactly("SYS-I3");
        assertThat(listed(inbox, "handledOutsideApp")).isEmpty();
        assertThat(inbox.toString()).doesNotContain("SYS-I1").doesNotContain("Inbox one").doesNotContain(SMO_A);
    }

    @Test
    void aSystemRetiredByTheRegisterLeavesTheInbox() {
        registered(
                List.of(listed("SYS-I1", "Inbox one", SMO_A, CM, "Q3")),
                List.of(listed("SYS-I2", "Inbox two", SMO_A, CM, "Q4")));

        assertThat(listed(inbox(signedInAs(SMO_A)), "systems")).containsExactly("SYS-I1");
    }

    @Test
    void aSystemMovedToAnotherSmoByTheRegisterMovesInboxes() {
        registered(listed("SYS-I1", "Inbox one", SMO_A, CM, "Q3"));
        ApiClient first = signedInAs(SMO_A);
        assertThat(listed(inbox(first), "systems")).containsExactly("SYS-I1");

        registered(listed("SYS-I1", "Inbox one", SMO_B, CM, "Q3"));

        assertThat(listed(inbox(signedInAs(SMO_B)), "systems")).containsExactly("SYS-I1");
        assertThat(first.get(INBOX).status()).as("the former SMO holds no role any more").isEqualTo(403);
    }

    @Test
    void aSystemMovedToAnotherQuarterByTheRegisterShowsTheNewQuartersDates() {
        registered(listed("SYS-I1", "Inbox one", SMO_A, CM, "Q3"));
        registered(listed("SYS-I1", "Inbox one", SMO_A, CM, "Q4"));

        JsonNode moved = item(inbox(signedInAs(SMO_A)), "systems", "SYS-I1");

        assertThat(moved.get("quarter").asInt()).isEqualTo(4);
        assertThat(moved.get("submitDue").asString()).isEqualTo("2027-11-14");
    }

    @Test
    void aVaPocWhoIsAlsoNamedAsAnSmoSeesOnlyTheSystemsTheyAreSmoFor() {
        registered(
                List.of(listed("SYS-I1", "Inbox one", POC_ONE, CM, "Q3"),
                        listed("SYS-I2", "Inbox two", SMO_B, CM, "Q3")),
                List.of());

        JsonNode inbox = inbox(vaPoc());

        assertThat(listed(inbox, "systems")).containsExactly("SYS-I1");
    }
}
