package sg.annualva.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Stories 16 and 71: the app knows the current VA Cycle, Quarter, week and window for today's business date, computed
 * in Singapore time from the controllable clock; the Cycles it runs, and while two overlap which are open.
 */
class CurrentPositionTest extends CycleSeam1 {

    @ParameterizedTest(name = "{0} is week {3} of Q{2} in Cycle {1}, the {4} window ending {5}")
    @CsvSource({
        "2027-08-02, 2027, 3, 1, SUBMISSION, 2027-08-15",
        "2027-08-08, 2027, 3, 1, SUBMISSION, 2027-08-15",
        "2027-08-09, 2027, 3, 2, SUBMISSION, 2027-08-15",
        "2027-08-15, 2027, 3, 2, SUBMISSION, 2027-08-15",
        "2027-08-16, 2027, 3, 3, REVIEW, 2027-08-29",
        "2027-08-29, 2027, 3, 4, REVIEW, 2027-08-29",
        "2027-08-30, 2027, 3, 5, ENDORSEMENT, 2027-10-31",
        "2027-10-31, 2027, 3, 13, ENDORSEMENT, 2027-10-31",
        "2027-11-01, 2027, 4, 1, SUBMISSION, 2027-11-14",
        "2028-02-06, 2027, 4, 14, ENDORSEMENT, 2028-02-06",
        "2028-02-07, 2028, 1, 1, SUBMISSION, 2028-02-20",
        "2028-04-30, 2028, 1, 12, ENDORSEMENT, 2028-04-30",
        "2028-05-01, 2028, 2, 1, SUBMISSION, 2028-05-14",
    })
    void todayFallsInTheRightQuarterWeekAndWindow(String day, int year, int quarter, int week, String window,
            String windowEnds) {
        ApiClient poc = signedInOn(day);

        JsonNode all = cycles(poc);

        assertThat(all.get("businessDate").asString()).isEqualTo(day);
        assertThat(all.get("currentCycle").asInt()).isEqualTo(year);
        JsonNode current = cycle(poc, year).get("current");
        assertThat(current.get("quarter").asInt()).isEqualTo(quarter);
        assertThat(current.get("week").asInt()).isEqualTo(week);
        assertThat(current.get("window").asString()).isEqualTo(window);
        assertThat(current.get("windowEndsOn").asString()).isEqualTo(windowEnds);
        assertThat(current(all, year)).as("the same answer in the list").isEqualTo(current);
    }

    @Test
    void theBusinessDateIsTheSingaporeDateNotTheUtcDate() {
        // 16:30 UTC on Sunday 1 August 2027 is 00:30 on Monday 2 August in Singapore: the first day of Q3.
        JsonNode atMidnightSingapore = cycles(signedInAt(Instant.parse("2027-08-01T16:30:00Z")));
        assertThat(atMidnightSingapore.get("businessDate").asString()).isEqualTo("2027-08-02");
        assertThat(current(atMidnightSingapore, 2027).get("quarter").asInt()).isEqualTo(3);
        assertThat(current(atMidnightSingapore, 2027).get("week").asInt()).isEqualTo(1);

        // 15:59 UTC on Sunday 1 August is still 23:59 on Sunday in Singapore: the last day of Q2.
        JsonNode beforeMidnight = cycles(signedInAt(Instant.parse("2027-08-01T15:59:00Z")));
        assertThat(beforeMidnight.get("businessDate").asString()).isEqualTo("2027-08-01");
        assertThat(current(beforeMidnight, 2027).get("quarter").asInt()).isEqualTo(2);
        assertThat(current(beforeMidnight, 2027).get("window").asString()).isEqualTo("ENDORSEMENT");

        // The Cycle changes at Singapore midnight too: 16:00 UTC on Sunday 6 February 2028 is Monday 7 February.
        JsonNode newCycle = cycles(signedInAt(Instant.parse("2028-02-06T16:00:00Z")));
        assertThat(newCycle.get("currentCycle").asInt()).isEqualTo(2028);
        assertThat(newCycle.get("businessDate").asString()).isEqualTo("2028-02-07");
    }

    @Test
    void whileTwoCyclesOverlapBothAreListedAndOnlyTheNewerHasACurrentQuarter() {
        ApiClient poc = signedInOn("2028-02-10");

        JsonNode all = cycles(poc);

        assertThat(years(all)).containsExactly(2027, 2028);
        assertThat(all.get("currentCycle").asInt()).isEqualTo(2028);
        assertThat(all.get("cycles").get(0).get("phase").asString()).as("Q4 ended 6 February").isEqualTo("CLOSING");
        assertThat(all.get("cycles").get(0).get("current").isNull()).isTrue();
        assertThat(all.get("cycles").get(1).get("phase").asString()).isEqualTo("RUNNING");
        assertThat(all.get("cycles").get(1).get("current").get("quarter").asInt()).isEqualTo(1);
    }

    @Test
    void aCycleStaysOpenThroughItsCloseDateAndIsClosedTheDayAfter() {
        // Cycle 2027's default close date is 2028-03-05, 28 days after Q4 ends on 2028-02-06.
        assertThat(phaseOf(cycles(signedInOn("2028-03-05")), 2027)).isEqualTo("CLOSING");
        assertThat(phaseOf(cycles(signedInOn("2028-03-06")), 2027)).isEqualTo("CLOSED");
        assertThat(phaseOf(cycles(signedInOn("2027-11-01")), 2027)).isEqualTo("RUNNING");
    }

    @Test
    void theCyclesListRunsFromTheFirstLiveCycleToTheOneRunningToday() {
        assertThat(years(cycles(signedInOn("2027-08-02")))).containsExactly(2027);
        assertThat(years(cycles(signedInOn("2030-06-17")))).containsExactly(2027, 2028, 2029, 2030);
        assertThat(cycles(signedInOn("2030-06-17")).get("currentCycle").asInt()).isEqualTo(2030);
        // January 2031 still belongs to Cycle 2030, whose Q4 runs to 2031-02-02.
        JsonNode january = cycles(signedInOn("2031-01-20"));
        assertThat(january.get("currentCycle").asInt()).isEqualTo(2030);
        assertThat(years(january)).containsExactly(2027, 2028, 2029, 2030);
    }

    @Test
    void beforeTheFirstLiveCycleStartsThereIsNoCurrentCycleAndTheFirstLiveCycleIsUpcoming() {
        JsonNode all = cycles(signedInOn("2027-01-15"));

        assertThat(all.get("currentCycle").isNull()).isTrue();
        assertThat(years(all)).containsExactly(2027);
        assertThat(phaseOf(all, 2027)).isEqualTo("UPCOMING");
        assertThat(all.get("cycles").get(0).get("current").isNull()).isTrue();
    }

    @Test
    void aCycleThatIsNotRunningTodayHasNoCurrentQuarter() {
        ApiClient poc = signedInOn("2027-08-02");

        assertThat(cycle(poc, 2028).get("current").isNull()).as("not started").isTrue();
        assertThat(cycle(poc, 2028).get("phase").asString()).isEqualTo("UPCOMING");
        assertThat(cycle(poc, 2026).get("current").isNull()).as("before the first live Cycle").isTrue();
        assertThat(cycle(poc, 2026).get("phase").asString()).isEqualTo("CLOSED");
    }

    private static JsonNode current(JsonNode all, int year) {
        for (JsonNode cycle : all.get("cycles")) {
            if (cycle.get("year").asInt() == year) {
                return cycle.get("current");
            }
        }
        throw new AssertionError("no Cycle " + year);
    }

    private static String phaseOf(JsonNode all, int year) {
        for (JsonNode cycle : all.get("cycles")) {
            if (cycle.get("year").asInt() == year) {
                return cycle.get("phase").asString();
            }
        }
        throw new AssertionError("no Cycle " + year);
    }

    private static List<Integer> years(JsonNode all) {
        var years = new java.util.ArrayList<Integer>();
        all.get("cycles").forEach(cycle -> years.add(cycle.get("year").asInt()));
        return years;
    }
}
