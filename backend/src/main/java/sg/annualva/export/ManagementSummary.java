package sg.annualva.export;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import sg.annualva.dashboard.CycleMatrix;
import sg.annualva.dashboard.SystemState;

/**
 * What a management summary holds (story 81), read when it is asked for and kept until its file is made: counts by Quarter
 * and state with Endorsed Late, and one line per System. Counts, System IDs and names, SMO/Tech Lead emails, states and
 * flags only; never a Finding. Withdrawn VA Submissions and Quarters Handled outside the app are not in it.
 *
 * @param cycle the VA Cycle's year
 * @param quarter the one Quarter it covers, or null for the whole Cycle
 * @param asAt the business date it was read on (Asia/Singapore)
 * @param quarters the Quarters it counts, in order: the one asked for, or every Quarter the app runs
 * @param systems one line per System, by Quarter then System ID
 */
record ManagementSummary(
        int cycle, @Nullable Integer quarter, Classification classification, LocalDate asAt,
        List<CycleMatrix.QuarterStrip> quarters, List<SystemLine> systems) {

    /**
     * One System of the summary.
     *
     * @param quarter 1 to 4
     * @param openDeviations how many of its Findings are Risk Accepted or Remediation Planned
     */
    record SystemLine(
            String systemId, String name, int quarter, String smoEmail, SystemState state, boolean overdue,
            boolean endorsedLate, long openDeviations) {}
}
