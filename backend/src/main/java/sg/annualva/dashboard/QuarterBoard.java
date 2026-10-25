package sg.annualva.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.Need;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.VaCycle;

/**
 * The Quarter board (story 49): one Quarter of a VA Cycle at once, its Systems in one column per state, from Not Started
 * to Not Endorsed in the order of the spec's state table, every column present even when it is empty. It is the one
 * dashboard reader ({@link DashboardSystems}) reading that Quarter only, with the {@link ReviewMarkers}. A VA POC (story
 * 64) reads it for {@link Need#ENDORSE} and sees every System of the Quarter; a Cyber Manager (story 49) reads it for
 * {@link Need#REVIEW} and sees only the Systems assigned to them in the System Register. A person who holds both roles
 * sees the VA POC's board, which holds the other. In a Quarter Handled outside the app every column is empty and its
 * Systems are listed apart, with nothing to do.
 */
@Service
public class QuarterBoard {

    /**
     * One System on the board.
     *
     * @param submissionId its VA Submission in the Cycle, or null while it is Not Started
     * @param overdue the Overdue flag the sweep set; nothing locks
     * @param confirmed the Findings whose current proposal the Cyber Manager confirmed
     * @param awaitingReview the Findings whose current proposal awaits the Cyber Manager's review
     * @param sodConflict whether segregation of duties refuses the caller the step the board is read for, the review
     *     (ticket 28) or, on a VA POC's board, the endorsement (ticket 34): they acted on the VA Submission at another
     *     step, or are assigned to another step of its System (its SMO/Tech Lead, say)
     * @param exemptionLapsed whether a conditional Exemption of it lapsed (ticket 42)
     * @param smoUnreachableOn on a VA POC's board, the business date the System was flagged SMO Unreachable (ticket 39,
     *     ADR 0052); null while it is not, and on a Cyber Manager's board
     */
    public record Card(
            UUID id, String systemId, String name, @Nullable UUID submissionId, boolean overdue, long confirmed,
            long awaitingReview, boolean sodConflict, boolean exemptionLapsed, @Nullable LocalDate smoUnreachableOn) {}

    /** The Systems in one state, in System ID order. */
    public record Column(SystemState state, List<Card> systems) {}

    /**
     * @param cycle the VA Cycle's year
     * @param quarter 1 to 4
     */
    public record Board(int cycle, int quarter, List<Column> columns, List<HandledOutside> handledOutsideApp) {}

    private final CycleCalendar calendar;
    private final DashboardSystems systems;
    private final ReviewMarkers markers;
    private final AccessGuard guard;

    QuarterBoard(CycleCalendar calendar, DashboardSystems systems, ReviewMarkers markers, AccessGuard guard) {
        this.calendar = calendar;
        this.systems = systems;
        this.markers = markers;
        this.guard = guard;
    }

    /**
     * @param requestedCycle the VA Cycle's year, or null for the one running today (before the first live Cycle starts,
     *     that Cycle); a Cycle the app does not run is not found
     * @param requestedQuarter 1 to 4, or null for the Quarter the Cycle shows today ({@link VaCycle#quarterShownOn})
     */
    @Transactional(readOnly = true)
    public Board board(@Nullable Integer requestedCycle, @Nullable Integer requestedQuarter) {
        VaCycle cycle = calendar.running(requestedCycle);
        int quarter = requestedQuarter == null ? cycle.quarterShownOn(calendar.today()).number() : requestedQuarter;
        Need need = guard.scope(Need.ENDORSE).all() ? Need.ENDORSE : Need.REVIEW;
        DashboardSystems.Rows rows = systems.in(cycle, need, quarter);
        Map<UUID, ReviewMarkers.Marker> marked = markers.of(rows.live(), need);
        Map<SystemState, List<Card>> byState = new EnumMap<>(SystemState.class);
        for (DashboardSystems.Row row : rows.live()) {
            byState.computeIfAbsent(row.state(), state -> new ArrayList<>())
                    .add(card(row, ReviewMarkers.of(marked, row), need == Need.ENDORSE));
        }
        List<Column> columns = new ArrayList<>();
        for (SystemState state : SystemState.values()) {
            columns.add(new Column(state, List.copyOf(byState.getOrDefault(state, List.of()))));
        }
        return new Board(cycle.year(), quarter, List.copyOf(columns), rows.handledOutside());
    }

    /** @param vaPoc whether the board is a VA POC's, the only one that shows the SMO Unreachable flag: theirs to fix */
    private static Card card(DashboardSystems.Row row, ReviewMarkers.Marker marker, boolean vaPoc) {
        return new Card(row.system().id(), row.system().systemId(), row.system().name(), row.submissionId(),
                row.overdue(), marker.confirmed(), marker.awaitingReview(), marker.sodConflict(), row.exemptionLapsed(),
                vaPoc ? row.system().smoUnreachableOn() : null);
    }
}
