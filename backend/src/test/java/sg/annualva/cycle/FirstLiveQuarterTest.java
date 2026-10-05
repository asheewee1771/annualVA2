package sg.annualva.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 16 (decision record, first live Quarter): VA Cycle 2027 goes live at Q3, so its earlier Quarters, and every
 * earlier Cycle, are Handled outside the app; every Quarter of a later Cycle is in the app.
 */
class FirstLiveQuarterTest extends CycleSeam1 {

    @Test
    void cycle2027GoesLiveAtQ3SoQ1AndQ2AreHandledOutsideTheApp() {
        JsonNode cycle = cycle(signedInOn("2027-08-02"), 2027);

        assertThat(cycle.get("firstLiveQuarter").asInt()).isEqualTo(3);
        assertThat(outside(cycle)).containsExactly(true, true, false, false);
    }

    @Test
    void aCycleBeforeTheFirstLiveCycleIsEntirelyHandledOutsideTheApp() {
        JsonNode cycle = cycle(signedInOn("2027-08-02"), 2026);

        assertThat(cycle.get("firstLiveQuarter").isNull()).isTrue();
        assertThat(outside(cycle)).containsExactly(true, true, true, true);
    }

    @Test
    void everyQuarterOfALaterCycleIsInTheApp() {
        ApiClient poc = signedInOn("2027-08-02");

        for (int year : List.of(2028, 2029, 2035)) {
            JsonNode cycle = cycle(poc, year);
            assertThat(cycle.get("firstLiveQuarter").asInt()).as("Cycle %d", year).isEqualTo(1);
            assertThat(outside(cycle)).as("Cycle %d", year).containsExactly(false, false, false, false);
        }
    }

    @Test
    void todayItselfIsMarkedWhenItFallsInAQuarterHandledOutsideTheApp() {
        // 3 May 2027 is the first day of Q2 of Cycle 2027, which the manual process still runs.
        JsonNode current = cycle(signedInOn("2027-05-03"), 2027).get("current");

        assertThat(current.get("quarter").asInt()).isEqualTo(2);
        assertThat(current.get("handledOutsideApp").asBoolean()).isTrue();
        assertThat(cycle(signedInOn("2027-08-02"), 2027).get("current").get("handledOutsideApp").asBoolean()).isFalse();
    }

    private static List<Boolean> outside(JsonNode cycle) {
        var flags = new ArrayList<Boolean>();
        cycle.get("quarters").forEach(quarter -> flags.add(quarter.get("handledOutsideApp").asBoolean()));
        return flags;
    }
}
