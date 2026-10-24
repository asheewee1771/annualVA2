package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 17 (ticket 41): a new VA Cycle opens when its Q1 starts and takes its Systems and Quarters from the standing
 * Register; while it overlaps the older Cycle, which is still taking late Q4 endorsements, both are open, and a Register
 * apply changes only the newer one.
 */
class CycleOverlapTest extends CycleLifecycleSeam1 {

    private static final String OPENED = "CYCLE_OPENED";

    private void registerQ3AndQ4() {
        registered(listed("SYS-O3", "Overlap three", SMO_A, CM, "Q3"), listed("SYS-O4", "Overlap four", SMO_A, CM, "Q4"));
    }

    @Test
    void theCyclesListMarksWhichAreOpenBeforeDuringAndAfterTheOverlap() {
        today("2028-02-06");
        JsonNode lastDayOfQ4 = cycles(vaPoc());
        today("2028-02-07");
        JsonNode firstDayOfQ1 = cycles(vaPoc());
        today("2028-03-05");
        JsonNode closeDate = cycles(vaPoc());
        today("2028-03-06");
        JsonNode afterTheClose = cycles(vaPoc());

        assertThat(openYears(lastDayOfQ4)).containsExactly(2027);
        assertThat(openYears(firstDayOfQ1)).as("Q4's endorsements still have 4 weeks").containsExactly(2027, 2028);
        assertThat(cycleOf(firstDayOfQ1, 2027).get("phase").asString()).isEqualTo("CLOSING");
        assertThat(cycleOf(firstDayOfQ1, 2027).get("closedAt").isNull()).isTrue();
        assertThat(openYears(closeDate)).as("open through its close date").containsExactly(2027, 2028);
        assertThat(openYears(afterTheClose)).containsExactly(2028);
        assertThat(cycleOf(afterTheClose, 2027).get("phase").asString()).isEqualTo("CLOSED");
    }

    @Test
    void theJobOpensTheNewCycleOnceWhenItsQ1StartsAndItTakesTheStandingRegister() {
        registerQ3AndQ4();
        today("2028-02-06");
        lifecycle();
        Set<String> before = auditIds(OPENED);
        assertThat(openYears(cycles(vaPoc()))).as("Q1 of Cycle 2028 has not started").containsExactly(2027);

        today("2028-02-07");
        lifecycle();
        lifecycle();

        List<JsonNode> opened = writtenSince(OPENED, before);
        assertThat(opened).as("once, however often the job runs").hasSize(1);
        assertThat(opened.get(0).get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(opened.get(0).get("actorId").isNull()).as("the job acts for nobody").isTrue();
        assertThat(opened.get(0).get("detail").get("cycle").asInt()).isEqualTo(2028);
        JsonNode newer = inbox(signedInAs(SMO_A), 2028);
        assertThat(systemIds(newer)).containsExactly("SYS-O3", "SYS-O4");
        assertThat(itemOf(newer, "SYS-O3").get("submitDue").asString()).isEqualTo("2028-08-20");
    }

    @Test
    void whileTwoCyclesOverlapARegisterApplyChangesOnlyTheNewerCycle() {
        registerQ3AndQ4();
        today("2028-02-10");
        lifecycle();

        registered(listed("SYS-O3", "Overlap three", SMO_A, CM, "Q1"), listed("SYS-O4", "Overlap four", SMO_A, CM, "Q4"),
                listed("SYS-O9", "Overlap nine", SMO_A, CM, "Q2"));
        ApiClient smo = signedInAs(SMO_A);
        JsonNode older = inbox(smo, 2027);
        JsonNode newer = inbox(smo, 2028);

        assertThat(systemIds(older)).as("as the Register was when Cycle 2028 opened").containsExactly("SYS-O3", "SYS-O4");
        assertThat(itemOf(older, "SYS-O3").get("quarter").asInt()).isEqualTo(3);
        assertThat(itemOf(older, "SYS-O3").get("submitDue").asString()).isEqualTo("2027-08-15");
        assertThat(systemIds(newer)).containsExactly("SYS-O3", "SYS-O9", "SYS-O4");
        assertThat(itemOf(newer, "SYS-O3").get("quarter").asInt()).isEqualTo(1);
    }

    @Test
    void aRegisterApplyOnTheFirstDayOfTheNewCycleChangesOnlyItEvenBeforeTheJobRuns() {
        registerQ3AndQ4();
        today("2028-02-07");

        registered(listed("SYS-O3", "Overlap three", SMO_A, CM, "Q3"), listed("SYS-O4", "Overlap four", SMO_A, CM, "Q4"),
                listed("SYS-O9", "Overlap nine", SMO_A, CM, "Q4"));
        arrange.overdueSweep();

        assertThat(systemIds(inbox(signedInAs(SMO_A), 2027))).containsExactly("SYS-O3", "SYS-O4");
        assertThat(entries("SUBMISSION_OVERDUE", idOf("SYS-O9")))
                .as("not Not Started in Cycle 2027, whose Q4 Submission Window has passed").isEmpty();
        assertThat(entries("SUBMISSION_OVERDUE", idOf("SYS-O4"))).as("SYS-O4 was Not Started in Q4 of 2027")
                .hasSize(1);
    }

    @Test
    void startingInTheOlderCycleUsesItsQuarterAndASystemAddedSinceIsNotInIt() {
        registerQ3AndQ4();
        today("2028-02-10");
        registered(listed("SYS-O3", "Overlap three", SMO_A, CM, "Q3"), listed("SYS-O4", "Overlap four", SMO_A, CM, "Q1"),
                listed("SYS-O9", "Overlap nine", SMO_A, CM, "Q4"));
        ApiClient smo = signedInAs(SMO_A);

        JsonNode olderStart = ApiClient.json(start(smo, idOf("SYS-O4"), 2027));
        var notInIt = start(smo, idOf("SYS-O9"), 2027);

        assertThat(olderStart.get("quarter").asInt()).as("Q4 in Cycle 2027, as it was when Cycle 2028 opened")
                .isEqualTo(4);
        assertThat(olderStart.get("submitDue").asString()).isEqualTo("2027-11-14");
        assertConflict(notInIt, "invalid-transition");
        assertThat(entries("SUBMISSION_STARTED", idOf("SYS-O9")).get(0).get("detail").get("reason").asString())
                .isEqualTo("not-in-cycle");
    }
}
