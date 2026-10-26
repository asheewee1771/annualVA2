package sg.annualva.export;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import sg.annualva.dashboard.CycleMatrix;
import sg.annualva.dashboard.SystemState;

/**
 * What a management summary holds (story 81), read when it is asked for and kept until its file is made: counts by Quarter
 * and state with Endorsed Late, and one line per System. Counts, System IDs and names, SMO/Tech Lead emails, states and
 * flags only; never a Finding. Withdrawn VA Submissions and Quarters Handled outside the app are not in it. It is the
 * export module's own shape, stored as JSON while the export is queued, so a change to a dashboard's response does not
 * change what a queued export reads back.
 *
 * @param cycle the VA Cycle's year
 * @param quarter the one Quarter it covers, or null for the whole Cycle
 * @param asAt the business date it was read on (Asia/Singapore)
 * @param quarters the Quarters it counts, in order: the one asked for, or every Quarter the app runs
 * @param systems one line per System, by Quarter then System ID
 */
record ManagementSummary(
        int cycle, @Nullable Integer quarter, Classification classification, LocalDate asAt, List<QuarterLine> quarters,
        List<SystemLine> systems) {

    /**
     * One Quarter of the summary.
     *
     * @param counts the number of Systems in each state but Withdrawn
     * @param endorsedLate how many of the Endorsed were endorsed after the Quarter's last day
     * @param exemptionLapsed how many Systems had a conditional Exemption lapse (ADR 0051), whatever their state now:
     *     an endorsement after a lapse is judged late against the Cycle's close, so this is what shows it came late
     *     (decided 2026-10-26)
     */
    record QuarterLine(
            int quarter, LocalDate startsOn, LocalDate endsOn, Map<SystemState, Long> counts, long total,
            long endorsedLate, long exemptionLapsed) {

        static QuarterLine of(CycleMatrix.QuarterStrip strip, long exemptionLapsed) {
            return new QuarterLine(strip.quarter(), strip.startsOn(), strip.endsOn(), strip.counts(), strip.total(),
                    strip.endorsedLate(), exemptionLapsed);
        }
    }

    /**
     * One System of the summary.
     *
     * @param quarter 1 to 4
     * @param exemptionLapsed whether a conditional Exemption of its VA Submission lapsed (ADR 0051)
     * @param openDeviations how many of its Findings are Risk Accepted or Remediation Planned
     */
    record SystemLine(
            String systemId, String name, int quarter, String smoEmail, SystemState state, boolean overdue,
            boolean endorsedLate, boolean exemptionLapsed, long openDeviations) {}
}
