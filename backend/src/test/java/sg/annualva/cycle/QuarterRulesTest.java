package sg.annualva.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 16: a VA Cycle is named after its starting year and has four Quarters that start on the first Monday of
 * February, May, August and November, each ending the day before the next starts. The property is checked for every
 * year from 2026 to 2035 against an independent reading of "first Monday" (a walk through the first seven days).
 */
class QuarterRulesTest extends CycleSeam1 {

    private static final List<Month> QUARTER_MONTHS = List.of(Month.FEBRUARY, Month.MAY, Month.AUGUST, Month.NOVEMBER);

    static Stream<Integer> years() {
        return IntStream.rangeClosed(2026, 2035).boxed();
    }

    private static LocalDate firstMonday(int year, Month month) {
        for (int day = 1; day <= 7; day++) {
            LocalDate candidate = LocalDate.of(year, month, day);
            if (candidate.getDayOfWeek() == DayOfWeek.MONDAY) {
                return candidate;
            }
        }
        throw new AssertionError("every week has a Monday");
    }

    @ParameterizedTest(name = "VA Cycle {0}")
    @MethodSource("years")
    void aCycleIsNamedAfterItsStartingYearAndItsQuartersStartOnTheFirstMonday(int year) {
        JsonNode cycle = cycle(signedInOn("2027-08-02"), year);

        assertThat(cycle.get("year").asInt()).isEqualTo(year);
        assertThat(cycle.get("quarters")).hasSize(4);
        for (int i = 0; i < 4; i++) {
            JsonNode quarter = cycle.get("quarters").get(i);
            LocalDate starts = LocalDate.parse(quarter.get("startsOn").asString());
            assertThat(quarter.get("number").asInt()).isEqualTo(i + 1);
            assertThat(starts).as("Q%d starts on the first Monday of %s", i + 1, QUARTER_MONTHS.get(i))
                    .isEqualTo(firstMonday(year, QUARTER_MONTHS.get(i)));
            assertThat(starts.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(starts.getDayOfMonth()).isBetween(1, 7);
        }
        assertThat(LocalDate.parse(cycle.get("startsOn").asString())).isEqualTo(firstMonday(year, Month.FEBRUARY));
    }

    @ParameterizedTest(name = "VA Cycle {0}")
    @MethodSource("years")
    void aQuarterEndsTheDayBeforeTheNextStartsAndQ4EndsTheDayBeforeTheNextCyclesQ1(int year) {
        var poc = signedInOn("2027-08-02");
        JsonNode cycle = cycle(poc, year);
        LocalDate nextCycleStarts = firstMonday(year + 1, Month.FEBRUARY);

        for (int i = 0; i < 4; i++) {
            JsonNode quarter = cycle.get("quarters").get(i);
            LocalDate starts = LocalDate.parse(quarter.get("startsOn").asString());
            LocalDate ends = LocalDate.parse(quarter.get("endsOn").asString());
            LocalDate nextStarts = i < 3 ? firstMonday(year, QUARTER_MONTHS.get(i + 1)) : nextCycleStarts;
            assertThat(ends).as("Q%d ends the day before the next Quarter starts", i + 1).isEqualTo(nextStarts.minusDays(1));
            long weeks = (ChronoUnit.DAYS.between(starts, ends) + 1) / 7;
            assertThat(ChronoUnit.DAYS.between(starts, ends) + 1).as("whole weeks").isEqualTo(weeks * 7);
            assertThat(weeks).as("12 to 14 weeks").isBetween(12L, 14L);
            assertThat(quarter.get("weeks").asLong()).isEqualTo(weeks);
        }
        assertThat(LocalDate.parse(cycle.get("endsOn").asString())).isEqualTo(nextCycleStarts.minusDays(1));
        assertThat(LocalDate.parse(cycle(poc, year + 1).get("quarters").get(0).get("startsOn").asString()))
                .as("Q4 ends the day before the next Cycle's Q1").isEqualTo(nextCycleStarts);
    }

    @ParameterizedTest(name = "VA Cycle {0}")
    @MethodSource("years")
    void everyQuarterExposesItsDueDatesAndTheCycleItsDefaultCloseDate(int year) {
        JsonNode cycle = cycle(signedInOn("2027-08-02"), year);

        for (JsonNode quarter : cycle.get("quarters")) {
            LocalDate starts = LocalDate.parse(quarter.get("startsOn").asString());
            assertThat(LocalDate.parse(quarter.get("submitDue").asString())).isEqualTo(starts.plusDays(13));
            assertThat(LocalDate.parse(quarter.get("reviewDue").asString())).isEqualTo(starts.plusDays(27));
            assertThat(quarter.get("endorseDue").asString()).isEqualTo(quarter.get("endsOn").asString());

            JsonNode windows = quarter.get("windows");
            assertThat(windows).hasSize(3);
            assertThat(windows.get(0).get("window").asString()).isEqualTo("SUBMISSION");
            assertThat(windows.get(0).get("startsOn").asString()).isEqualTo(starts.toString());
            assertThat(windows.get(0).get("endsOn").asString()).isEqualTo(starts.plusDays(13).toString());
            assertThat(windows.get(1).get("window").asString()).isEqualTo("REVIEW");
            assertThat(windows.get(1).get("startsOn").asString()).isEqualTo(starts.plusDays(14).toString());
            assertThat(windows.get(1).get("endsOn").asString()).isEqualTo(starts.plusDays(27).toString());
            assertThat(windows.get(2).get("window").asString()).isEqualTo("ENDORSEMENT");
            assertThat(windows.get(2).get("startsOn").asString()).isEqualTo(starts.plusDays(28).toString());
            assertThat(windows.get(2).get("endsOn").asString()).isEqualTo(quarter.get("endsOn").asString());
        }
        LocalDate q4Ends = LocalDate.parse(cycle.get("quarters").get(3).get("endsOn").asString());
        assertThat(cycle.get("closesOn").asString()).as("Q4's end plus 28 days").isEqualTo(q4Ends.plusDays(28).toString());
    }

    /** The cases the spec calls out: 2027's Q4 is 14 weeks, 2028's Q1 is 12 and its Q2 14. */
    @Test
    void theQuarterLengthsThatDifferFromThirteenWeeksAreTheOnesTheSpecNames() {
        var poc = signedInOn("2027-08-02");

        JsonNode c2027 = cycle(poc, 2027);
        assertThat(starts(c2027)).containsExactly("2027-02-01", "2027-05-03", "2027-08-02", "2027-11-01");
        assertThat(c2027.get("quarters").get(3).get("weeks").asInt()).isEqualTo(14);
        assertThat(c2027.get("quarters").get(3).get("endsOn").asString()).isEqualTo("2028-02-06");
        assertThat(c2027.get("closesOn").asString()).isEqualTo("2028-03-05");

        JsonNode c2028 = cycle(poc, 2028);
        assertThat(starts(c2028)).containsExactly("2028-02-07", "2028-05-01", "2028-08-07", "2028-11-06");
        assertThat(c2028.get("quarters").get(0).get("weeks").asInt()).isEqualTo(12);
        assertThat(c2028.get("quarters").get(1).get("weeks").asInt()).isEqualTo(14);
        JsonNode shortQuarter = c2028.get("quarters").get(0);
        assertThat(shortQuarter.get("endorseDue").asString()).as("week 12 is its last day").isEqualTo("2028-04-30");
        JsonNode q3 = cycle(poc, 2027).get("quarters").get(2);
        assertThat(q3.get("submitDue").asString()).isEqualTo("2027-08-15");
        assertThat(q3.get("reviewDue").asString()).isEqualTo("2027-08-29");
        assertThat(q3.get("endorseDue").asString()).isEqualTo("2027-10-31");
    }

    private static List<String> starts(JsonNode cycle) {
        var starts = new java.util.ArrayList<String>();
        cycle.get("quarters").forEach(quarter -> starts.add(quarter.get("startsOn").asString()));
        return starts;
    }

    @Test
    void aYearOutsideTheSupportedRangeIsNotFoundAndAnythingElseIsABadRequest() {
        ApiClient poc = signedInOn("2027-08-02");

        for (String year : List.of("1999", "2100", "0", "-5")) {
            var response = poc.get("/api/cycles/" + year);
            assertThat(response.status()).as(year).isEqualTo(404);
            assertThat(response.header("content-type")).startsWith("application/problem+json");
        }
        for (String year : List.of("abc", "20x7", "2027.5")) {
            var response = poc.get("/api/cycles/" + year);
            assertThat(response.status()).as(year).isEqualTo(400);
            assertThat(ApiClient.json(response).get("detail").asString()).as("the value is not echoed")
                    .isEqualTo("A request parameter has a value of the wrong form.");
        }
        assertThat(poc.get("/api/cycles/2000").status()).isEqualTo(200);
        assertThat(poc.get("/api/cycles/2099").status()).isEqualTo(200);
    }
}
