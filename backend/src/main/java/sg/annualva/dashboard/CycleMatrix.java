package sg.annualva.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.Need;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.submission.Submissions;

/**
 * The VA POC's Cycle matrix (story 64): the whole VA Cycle at once, as a strip of its four Quarters with how many
 * Systems each holds in each state, then one row per System. The Systems are the one dashboard reader's
 * ({@link DashboardSystems}) for {@link Need#ENDORSE}, which is every System, so the matrix, the board and the inboxes
 * never disagree about a System's state. Withdrawn VA Submissions and Quarters Handled outside the app are left out of the
 * counts; the Systems of those Quarters are listed apart.
 */
@Service
public class CycleMatrix {

    /** Where a Quarter stands on today's business date. */
    public enum Phase {
        /** Before the first live Quarter: the manual process runs it, and it is left out of counts. */
        HANDLED_OUTSIDE_APP,
        /** Its last day has passed. */
        CLOSED,
        /** Today falls in it. */
        CURRENT,
        /** It has not started. */
        UPCOMING
    }

    /**
     * One Quarter of the strip.
     *
     * @param quarter 1 to 4
     * @param counts the number of Systems in each state, every state but Withdrawn, zeros included; all zero for a
     *     Quarter Handled outside the app
     * @param total the sum of the counts
     * @param endorsedLate how many of the Endorsed were endorsed after the Quarter's last day
     */
    public record QuarterStrip(
            int quarter, LocalDate startsOn, LocalDate endsOn, Phase phase, Map<SystemState, Long> counts, long total,
            long endorsedLate) {

        /** The same Quarter counted over these rows: those of this Quarter, in every state but Withdrawn. */
        QuarterStrip recount(List<SystemRow> rows) {
            Map<SystemState, Long> counted = new EnumMap<>(SystemState.class);
            for (SystemState state : SystemState.values()) {
                if (state != SystemState.WITHDRAWN) {
                    counted.put(state, 0L);
                }
            }
            long late = 0;
            for (SystemRow row : rows) {
                if (row.quarter() == quarter && counted.containsKey(row.state())) {
                    counted.merge(row.state(), 1L, Long::sum);
                    late += row.state() == SystemState.ENDORSED && row.endorsedLate() ? 1 : 0;
                }
            }
            long sum = counted.values().stream().mapToLong(Long::longValue).sum();
            return new QuarterStrip(quarter, startsOn, endsOn, phase, Collections.unmodifiableMap(counted), sum, late);
        }
    }

    /**
     * One milestone of a System's VA Submission: submitted, reviewed (passed to endorsement) or endorsed.
     *
     * @param dueOn the last day of the window it is due in (a business date in Asia/Singapore)
     * @param doneOn the business date it was last reached; null until it is
     * @param current whether the System's state waits on this milestone now, by the one window rule (ADR 0033 item 2):
     *     submitted for Not Started and Draft, reviewed for In Review and Returned for Rework, endorsed for Awaiting
     *     Endorsement; no milestone for a final or frozen state. The row's Overdue flag says whether its window passed
     */
    public record Milestone(LocalDate dueOn, @Nullable LocalDate doneOn, boolean current) {}

    /**
     * One System of the Cycle, in a Quarter the app runs.
     *
     * @param quarter 1 to 4
     * @param smoEmail the SMO/Tech Lead the Register names for it
     * @param submissionId its VA Submission in the Cycle, or null while it is Not Started
     * @param overdue the Overdue flag the sweep set (ADR 0033); nothing locks
     * @param endorsedLate whether its VA Submission was endorsed after the Quarter's last day
     * @param exemptionLapsed whether a conditional Exemption of it lapsed (ticket 42)
     */
    public record SystemRow(
            UUID id, String systemId, String name, int quarter, String smoEmail, SystemState state,
            @Nullable UUID submissionId, boolean overdue, boolean endorsedLate, Milestone submitted, Milestone reviewed,
            Milestone endorsed, boolean exemptionLapsed) {}

    /**
     * @param cycle the VA Cycle's year
     * @param systems one row per System in a Quarter the app runs, by Quarter then System ID; a Withdrawn one is listed,
     *     though left out of the counts
     */
    public record Matrix(
            int cycle, List<QuarterStrip> quarters, List<SystemRow> systems, List<HandledOutside> handledOutsideApp) {

        /**
         * The same matrix over only these Systems: only they have rows, and the strip counts them alone by the same rule
         * (an export of some Systems, ticket 44).
         *
         * @param kept the Systems' own ids
         */
        public Matrix restrictedTo(Set<UUID> kept) {
            List<SystemRow> rows = systems.stream().filter(row -> kept.contains(row.id())).toList();
            return new Matrix(cycle, quarters.stream().map(strip -> strip.recount(rows)).toList(), rows,
                    handledOutsideApp);
        }
    }

    private final CycleCalendar calendar;
    private final DashboardSystems systems;

    CycleMatrix(CycleCalendar calendar, DashboardSystems systems) {
        this.calendar = calendar;
        this.systems = systems;
    }

    /**
     * @param requested the VA Cycle's year, or null for the one running today (before the first live Cycle starts, that
     *     Cycle); a Cycle the app does not run is not found
     */
    @Transactional(readOnly = true)
    public Matrix matrix(@Nullable Integer requested) {
        VaCycle cycle = calendar.running(requested);
        DashboardSystems.Rows rows = systems.in(cycle, Need.ENDORSE);
        List<SystemRow> matrixRows = rows.live().stream().map(this::row).toList();
        LocalDate today = calendar.today();
        List<QuarterStrip> strip = new ArrayList<>();
        for (Quarter quarter : cycle.quarters()) {
            strip.add(new QuarterStrip(quarter.number(), quarter.startsOn(), quarter.endsOn(), phase(quarter, today),
                    Map.of(), 0, 0).recount(matrixRows));
        }
        return new Matrix(cycle.year(), List.copyOf(strip), matrixRows, rows.handledOutside());
    }

    private SystemRow row(DashboardSystems.Row row) {
        Submissions.Milestones reached = row.reached();
        return new SystemRow(row.system().id(), row.system().systemId(), row.system().name(), row.system().quarter(),
                row.system().smoEmail(), row.state(), row.submissionId(), row.overdue(), row.endorsedLate(),
                milestone(row, row.submitDue(), reached.submitted()),
                milestone(row, row.reviewDue(), reached.passedToEndorsement()),
                milestone(row, row.endorseDue(), reached.endorsed()), row.exemptionLapsed());
    }

    /** A milestone is current when its window is the one the state waits on: the row's {@code dueOn} is its due day. */
    private Milestone milestone(DashboardSystems.Row row, LocalDate dueOn, @Nullable Instant reachedAt) {
        return new Milestone(dueOn, reachedAt == null ? null : calendar.dateOf(reachedAt), dueOn.equals(row.dueOn()));
    }

    private static Phase phase(Quarter quarter, LocalDate today) {
        if (quarter.handledOutsideApp()) {
            return Phase.HANDLED_OUTSIDE_APP;
        }
        if (today.isAfter(quarter.endsOn())) {
            return Phase.CLOSED;
        }
        return quarter.contains(today) ? Phase.CURRENT : Phase.UPCOMING;
    }
}
