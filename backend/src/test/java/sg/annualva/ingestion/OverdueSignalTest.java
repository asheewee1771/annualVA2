package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.Metrics;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): the Overdue gauges count what the sweep has flagged, a VA Submission and a System with
 * none, and read the same on a second sweep. Q3 of VA Cycle 2027: the Submission Window ends on 15 August.
 */
class OverdueSignalTest extends SubmitSeam1 {

    private static final String SUBMISSIONS = "annualva_submissions_overdue{kind=\"submission\"}";
    private static final String NOT_STARTED = "annualva_submissions_overdue{kind=\"not_started\"}";

    @Test
    void theGaugesRiseWhenTheSweepFlagsADraftAndASystemWithNoSubmissionAndHoldOnASecondSweep() {
        draft();
        Metrics before = metrics();

        today("2027-08-15");
        arrange.overdueSweep();
        Metrics lastDay = metrics();
        today("2027-08-16");
        arrange.overdueSweep();
        Metrics flagged = metrics();
        arrange.overdueSweep();
        Metrics again = metrics();

        assertThat(lastDay.value(SUBMISSIONS)).as("the window's last day is still inside it").isEqualTo(before.value(SUBMISSIONS));
        assertThat(flagged.value(SUBMISSIONS)).as("SYS-F1's Draft").isEqualTo(before.value(SUBMISSIONS) + 1);
        assertThat(flagged.value(NOT_STARTED)).as("SYS-F2, with no VA Submission").isEqualTo(before.value(NOT_STARTED) + 1);
        assertThat(again.value(SUBMISSIONS)).isEqualTo(flagged.value(SUBMISSIONS));
        assertThat(again.value(NOT_STARTED)).isEqualTo(flagged.value(NOT_STARTED));
    }
}
