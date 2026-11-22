package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.annualva.harness.Metrics.OVERDUE_NOT_STARTED;
import static sg.annualva.harness.Metrics.OVERDUE_SUBMISSIONS;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.Metrics;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): the Overdue gauges count what the sweep has flagged, a VA Submission and a System with
 * none, and read the same on a second sweep. The gauges hold what the last sweep counted, so the test sweeps once before it
 * reads where they start. Q3 of VA Cycle 2027: the Submission Window ends on 15 August.
 */
class OverdueSignalTest extends SubmitSeam1 {

    @Test
    void theGaugesRiseWhenTheSweepFlagsADraftAndASystemWithNoSubmissionAndHoldOnASecondSweep() {
        draft();
        arrange.overdueSweep();
        Metrics before = metrics();

        today("2027-08-15");
        arrange.overdueSweep();
        Metrics lastDay = metrics();
        today("2027-08-16");
        arrange.overdueSweep();
        Metrics flagged = metrics();
        arrange.overdueSweep();
        Metrics again = metrics();

        assertThat(lastDay.since(before, OVERDUE_SUBMISSIONS)).as("the window's last day is still inside it").isZero();
        assertThat(flagged.since(before, OVERDUE_SUBMISSIONS)).as("SYS-F1's Draft").isEqualTo(1);
        assertThat(flagged.since(before, OVERDUE_NOT_STARTED)).as("SYS-F2, with no VA Submission").isEqualTo(1);
        assertThat(again.since(flagged, OVERDUE_SUBMISSIONS)).isZero();
        assertThat(again.since(flagged, OVERDUE_NOT_STARTED)).isZero();
    }
}
