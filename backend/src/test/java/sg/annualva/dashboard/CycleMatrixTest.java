package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 64, second tab: {@code GET /api/dashboards/cycle-matrix?cycle=} shows a VA POC the whole VA Cycle: a strip of its
 * four Quarters with the count of Systems in each state, then one row per System with its milestones (submitted,
 * reviewed, endorsed) against the day each is due. Withdrawn VA Submissions and Quarters Handled outside the app are left
 * out of the counts. The default clock is the first day of Q3 of VA Cycle 2027, its first live Quarter.
 */
class CycleMatrixTest extends CyberManagerSeam1 {

    static final String MATRIX = "/api/dashboards/cycle-matrix";

    private static JsonNode quarter(JsonNode matrix, int number) {
        JsonNode quarter = matrix.get("quarters").get(number - 1);
        assertThat(quarter.get("quarter").asInt()).isEqualTo(number);
        return quarter;
    }

    @Test
    void theStripCountsEachQuarterByStateLeavingOutWithdrawnAndQuartersHandledOutsideTheApp() {
        registered(listed("SYS-C1", "Not started", SMO_A, CM, "Q3"), listed("SYS-C2", "Draft", SMO_A, CM, "Q3"),
                listed("SYS-C3", "In Review", SMO_A, CM_OTHER, "Q3"), listed("SYS-C4", "Endorsed late", SMO_B, CM, "Q3"),
                listed("SYS-C5", "Endorsed", SMO_B, CM, "Q3"), listed("SYS-C6", "Withdrawn", SMO_B, CM, "Q3"),
                listed("SYS-C7", "Next Quarter", SMO_A, CM, "Q4"), listed("SYS-C8", "Outside", SMO_A, CM, "Q1"));
        ApiClient smoA = signedInAs(SMO_A);
        ApiClient smoB = signedInAs(SMO_B);
        started(smoA, "SYS-C2");
        arrange.submissionState(started(smoA, "SYS-C3"), "IN_REVIEW");
        String late = started(smoB, "SYS-C4");
        arrange.submissionState(late, "ENDORSED");
        arrange.endorsedLate(late);
        arrange.submissionState(started(smoB, "SYS-C5"), "ENDORSED");
        arrange.submissionState(started(smoB, "SYS-C6"), "WITHDRAWN");

        JsonNode matrix = ok(vaPoc(), MATRIX);

        assertThat(matrix.get("cycle").asInt()).isEqualTo(2027);
        assertThat(matrix.get("quarters")).hasSize(4);
        JsonNode first = quarter(matrix, 1);
        assertThat(first.get("phase").asString()).isEqualTo("HANDLED_OUTSIDE_APP");
        assertThat(first.get("startsOn").asString()).isEqualTo("2027-02-01");
        assertThat(first.get("total").asInt()).as("SYS-C8 is handled outside the app").isZero();
        assertThat(quarter(matrix, 2).get("phase").asString()).isEqualTo("HANDLED_OUTSIDE_APP");
        JsonNode current = quarter(matrix, 3);
        assertThat(current.get("phase").asString()).isEqualTo("CURRENT");
        assertThat(current.get("startsOn").asString()).isEqualTo("2027-08-02");
        assertThat(current.get("endsOn").asString()).isEqualTo("2027-10-31");
        JsonNode counts = current.get("counts");
        assertThat(counts.get("NOT_STARTED").asInt()).isEqualTo(1);
        assertThat(counts.get("DRAFT").asInt()).isEqualTo(1);
        assertThat(counts.get("IN_REVIEW").asInt()).isEqualTo(1);
        assertThat(counts.get("ENDORSED").asInt()).isEqualTo(2);
        assertThat(counts.get("AWAITING_ENDORSEMENT").asInt()).isZero();
        assertThat(counts.has("WITHDRAWN")).as("Withdrawn is left out of counts").isFalse();
        assertThat(current.get("total").asInt()).isEqualTo(5);
        assertThat(current.get("endorsedLate").asInt()).isEqualTo(1);
        JsonNode next = quarter(matrix, 4);
        assertThat(next.get("phase").asString()).isEqualTo("UPCOMING");
        assertThat(next.get("counts").get("NOT_STARTED").asInt()).isEqualTo(1);
        assertThat(next.get("total").asInt()).isEqualTo(1);
        assertThat(systemIds(matrix.get("handledOutsideApp"))).containsExactly("SYS-C8");
        assertThat(systemIds(matrix.get("systems"))).as("Withdrawn keeps its row; only the counts leave it out")
                .containsExactly("SYS-C1", "SYS-C2", "SYS-C3", "SYS-C4", "SYS-C5", "SYS-C6", "SYS-C7");
        assertThat(matrix.get("systems").get(5).get("state").asString()).isEqualTo("WITHDRAWN");
    }

    @Test
    void eachSystemHasARowWithTheBusinessDateItReachedEachMilestoneAgainstTheDayItWasDue() {
        registered(listed("SYS-R1", "Not started", SMO_A, CM, "Q3"), listed("SYS-R2", "In Review", SMO_A, CM, "Q3"),
                listed("SYS-R3", "Endorsed late", SMO_A, CM, "Q3"), listed("SYS-R4", "Returned", SMO_A, CM, "Q3"),
                listed("SYS-R5", "Next Quarter", SMO_A, CM, "Q4"));
        ApiClient smo = signedInAs(SMO_A);
        String inReview = started(smo, "SYS-R2");
        arrange.submissionState(inReview, "IN_REVIEW");
        arrange.milestones(inReview, "2027-08-09T17:30:00Z", null, null);
        String endorsed = started(smo, "SYS-R3");
        arrange.submissionState(endorsed, "ENDORSED");
        arrange.milestones(endorsed, "2027-08-12T02:00:00Z", "2027-08-27T02:00:00Z", "2027-11-02T16:30:00Z");
        arrange.endorsedLate(endorsed);
        String returned = started(smo, "SYS-R4");
        arrange.submissionState(returned, "RETURNED_FOR_REWORK");
        arrange.milestones(returned, "2027-08-13T02:00:00Z", null, null);
        today("2027-11-08");
        arrange.overdueSweep();

        JsonNode systems = ok(vaPoc(), MATRIX).get("systems");

        assertThat(systemIds(systems)).containsExactly("SYS-R1", "SYS-R2", "SYS-R3", "SYS-R4", "SYS-R5");
        JsonNode notStarted = systems.get(0);
        assertThat(notStarted.get("state").asString()).isEqualTo("NOT_STARTED");
        assertThat(notStarted.get("quarter").asInt()).isEqualTo(3);
        assertThat(notStarted.get("overdue").asBoolean()).isTrue();
        assertThat(notStarted.get("submissionId").isNull()).isTrue();
        assertMilestone(notStarted.get("submitted"), "2027-08-15", null, true);
        assertMilestone(notStarted.get("reviewed"), "2027-08-29", null, false);
        assertMilestone(notStarted.get("endorsed"), "2027-10-31", null, false);
        JsonNode review = systems.get(1);
        assertThat(review.get("submissionId").asString()).isEqualTo(inReview);
        assertMilestone(review.get("submitted"), "2027-08-15", "2027-08-10", false);
        assertMilestone(review.get("reviewed"), "2027-08-29", null, true);
        JsonNode late = systems.get(2);
        assertThat(late.get("state").asString()).isEqualTo("ENDORSED");
        assertThat(late.get("endorsedLate").asBoolean()).isTrue();
        assertThat(late.get("overdue").asBoolean()).isFalse();
        assertMilestone(late.get("submitted"), "2027-08-15", "2027-08-12", false);
        assertMilestone(late.get("reviewed"), "2027-08-29", "2027-08-27", false);
        assertMilestone(late.get("endorsed"), "2027-10-31", "2027-11-03", false);
        JsonNode rework = systems.get(3);
        assertMilestone(rework.get("submitted"), "2027-08-15", "2027-08-13", false);
        assertMilestone(rework.get("reviewed"), "2027-08-29", null, true);
        assertMilestone(systems.get(4).get("submitted"), "2027-11-14", null, true);
    }

    private static void assertMilestone(JsonNode milestone, String dueOn, String doneOn, boolean current) {
        assertThat(milestone.get("dueOn").asString()).isEqualTo(dueOn);
        if (doneOn == null) {
            assertThat(milestone.get("doneOn").isNull()).as("not reached: " + milestone).isTrue();
        } else {
            assertThat(milestone.get("doneOn").asString()).isEqualTo(doneOn);
        }
        assertThat(milestone.get("current").asBoolean()).as("current: " + milestone).isEqualTo(current);
    }

    @Test
    void aQuarterWhoseLastDayHasPassedIsClosedAndAnEarlierCycleCanBeAskedFor() {
        registered(listed("SYS-D1", "Every year", SMO_A, CM, "Q3"));
        today("2027-11-01");
        ApiClient poc = vaPoc();

        JsonNode matrix = ok(poc, MATRIX + "?cycle=2027");

        assertThat(quarter(matrix, 3).get("phase").asString()).isEqualTo("CLOSED");
        assertThat(quarter(matrix, 4).get("phase").asString()).isEqualTo("CURRENT");
        assertThat(poc.get(MATRIX + "?cycle=2019").status()).as("a Cycle the app does not run").isEqualTo(404);
    }

    @Test
    void anSmoACyberManagerAnAuditorAndAPersonWithNoRoleAreForbiddenAndAnonymousIsUnauthenticated() {
        registered(listed("SYS-X2", "Access", SMO_A, CM, "Q3"));
        ApiClient auditor = signedInAs("matrix.auditor@ci.annualva.test");
        arrange.grant("AUDITOR", "matrix.auditor@ci.annualva.test", null, "GRANT", "2027-12-31T00:00:00Z");

        var smo = signedInAs(SMO_A).get(MATRIX);
        var manager = signedInAs(CM).get(MATRIX);

        assertThat(smo.status()).isEqualTo(403);
        assertThat(smo.body()).doesNotContain("SYS-X2");
        assertThat(manager.status()).isEqualTo(403);
        assertThat(manager.body()).doesNotContain("SYS-X2");
        assertThat(auditor.get(MATRIX).status()).isEqualTo(403);
        assertThat(signedInAs("matrix.nobody@ci.annualva.test").get(MATRIX).status()).isEqualTo(403);
        assertThat(anotherBrowser().get(MATRIX).status()).isEqualTo(401);
    }
}
