package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * The inbox is for one VA Cycle: the one running today unless the caller picks another the app runs (the selector shown
 * while two Cycles overlap). A Cycle the app does not run is not found, and a value that is not a year is a bad request.
 */
class SmoInboxCycleTest extends InboxSeam1 {

    private void registerSystems() {
        registered(
                List.of(listed("SYS-C1", "Cycle one", SMO_A, CM, "Q1"),
                        listed("SYS-C3", "Cycle three", SMO_A, CM, "Q3")),
                List.of());
    }

    @Test
    void theDefaultIsTheCycleRunningTodayWhereOnlyTheFirstLiveQuartersAreLive() {
        registerSystems();

        JsonNode inbox = inbox(signedInAs(SMO_A));

        assertThat(inbox.get("cycle").asInt()).isEqualTo(2027);
        assertThat(listed(inbox, "systems")).containsExactly("SYS-C3");
        assertThat(listed(inbox, "handledOutsideApp")).containsExactly("SYS-C1");
    }

    @Test
    void whileTwoCyclesOverlapTheNewerIsTheDefaultAndTheOlderCanBePicked() {
        registerSystems();
        today("2028-02-10");
        ApiClient smo = signedInAs(SMO_A);

        JsonNode newer = inbox(smo);
        JsonNode older = inbox(smo, 2027);

        assertThat(newer.get("cycle").asInt()).isEqualTo(2028);
        assertThat(listed(newer, "systems")).as("every Quarter of Cycle 2028 is live").containsExactly("SYS-C1", "SYS-C3");
        assertThat(listed(newer, "handledOutsideApp")).isEmpty();
        JsonNode q1 = item(newer, "systems", "SYS-C1");
        assertThat(q1.get("submitDue").asString()).isEqualTo("2028-02-20");
        assertThat(q1.get("reviewDue").asString()).isEqualTo("2028-03-05");
        assertThat(q1.get("endorseDue").asString()).isEqualTo("2028-04-30");
        assertThat(older.get("cycle").asInt()).isEqualTo(2027);
        assertThat(listed(older, "handledOutsideApp")).containsExactly("SYS-C1");
        assertThat(item(older, "systems", "SYS-C3").get("submitDue").asString()).isEqualTo("2027-08-15");
    }

    @Test
    void beforeTheFirstLiveCycleStartsTheInboxIsThatCyclesUpcomingQuarters() {
        registerSystems();
        today("2027-01-15");
        ApiClient smo = signedInAs(SMO_A);

        JsonNode inbox = inbox(smo);

        assertThat(inbox.get("cycle").asInt()).isEqualTo(2027);
        assertThat(listed(inbox, "systems")).containsExactly("SYS-C3");
    }

    @Test
    void aCycleTheAppDoesNotRunIsNotFound() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);

        for (int year : new int[] {2026, 2028, 1999}) {
            var response = smo.get(INBOX + "?cycle=" + year);
            assertThat(response.status()).as("cycle " + year).isEqualTo(404);
            assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/not-found");
        }
    }

    @Test
    void aCycleThatIsNotAYearIsABadRequestThatDoesNotEchoTheValue() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);

        var response = smo.get(INBOX + "?cycle=second-system-secret");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("second-system-secret");
    }
}
