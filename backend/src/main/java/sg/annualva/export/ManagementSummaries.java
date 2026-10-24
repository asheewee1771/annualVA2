package sg.annualva.export;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.dashboard.CycleMatrix;
import sg.annualva.dashboard.SystemState;
import sg.annualva.finding.Findings;
import sg.annualva.web.RejectedRequestException;

/**
 * Reads a management summary (story 81) through the Cycle matrix (ADR 0037), once, so the summary, the matrix and the
 * boards never disagree about a System's state, its Overdue flag or Endorsed Late, and through the Findings for each VA
 * Submission's open Deviations. It reads with the caller's own access (the endorse scope, every System for a VA POC).
 * The summary is Confidential when it covers every System of the Quarter or Cycle asked for, and Restricted /
 * Sensitive-Normal when it covers only some (assumption A-1); the matrix then counts those alone, by its own rule.
 */
@Component
class ManagementSummaries {

    private final CycleMatrix matrix;
    private final Findings findings;
    private final CycleCalendar calendar;

    ManagementSummaries(CycleMatrix matrix, Findings findings, CycleCalendar calendar) {
        this.matrix = matrix;
        this.findings = findings;
        this.calendar = calendar;
    }

    /**
     * @param cycle the VA Cycle's year, or null for the one running today; one the app does not run is not found
     * @param quarter 1 to 4 for one Quarter, or null for the whole Cycle
     * @param only the Systems' own ids to cover, or null for every System; each must be a System of the Quarter or
     *     Cycle that is not Withdrawn
     * @throws RejectedRequestException 400 {@code invalid-field} for a Quarter Handled outside the app or a System that
     *     is not one of those
     */
    ManagementSummary read(@Nullable Integer cycle, @Nullable Integer quarter, @Nullable Set<UUID> only) {
        CycleMatrix.Matrix read = matrix.matrix(cycle);
        List<CycleMatrix.QuarterStrip> counted = read.quarters().stream()
                .filter(strip -> strip.phase() != CycleMatrix.Phase.HANDLED_OUTSIDE_APP)
                .filter(strip -> quarter == null || strip.quarter() == quarter)
                .toList();
        if (quarter != null && counted.isEmpty()) {
            throw RejectedRequestException.invalidField("quarter",
                    "That Quarter is handled outside the app, so the app has nothing to summarise for it.");
        }
        Set<UUID> every = read.systems().stream()
                .filter(row -> row.state() != SystemState.WITHDRAWN)
                .filter(row -> quarter == null || row.quarter() == quarter)
                .map(CycleMatrix.SystemRow::id)
                .collect(Collectors.toSet());
        if (only != null && !every.containsAll(only)) {
            throw RejectedRequestException.invalidField("systems",
                    "Choose only Systems of the Quarter or Cycle the summary is about that are not Withdrawn.");
        }
        boolean whole = only == null || only.equals(every);
        CycleMatrix.Matrix covered = read.restrictedTo(whole ? every : only);
        Set<Integer> quarters = counted.stream().map(CycleMatrix.QuarterStrip::quarter).collect(Collectors.toSet());
        Map<UUID, Long> deviations = findings.deviationCounts(
                covered.systems().stream().map(CycleMatrix.SystemRow::submissionId).filter(Objects::nonNull).toList());
        return new ManagementSummary(covered.cycle(), quarter,
                whole ? Classification.CONFIDENTIAL : Classification.RESTRICTED, calendar.today(),
                covered.quarters().stream().filter(strip -> quarters.contains(strip.quarter()))
                        .map(ManagementSummary.QuarterLine::of).toList(),
                covered.systems().stream().map(row -> line(row, deviations)).toList());
    }

    private static ManagementSummary.SystemLine line(CycleMatrix.SystemRow row, Map<UUID, Long> deviations) {
        UUID submission = row.submissionId();
        return new ManagementSummary.SystemLine(row.systemId(), row.name(), row.quarter(), row.smoEmail(), row.state(),
                row.overdue(), row.endorsedLate(), submission == null ? 0 : deviations.getOrDefault(submission, 0L));
    }
}
