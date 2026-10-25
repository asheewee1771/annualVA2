package sg.annualva.dashboard;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.Need;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.VaCycle;

/**
 * The Cyber Manager's action inbox (story 48): the Systems assigned to the caller in the System Register, in one VA Cycle,
 * each with its VA Submission's state (Not Started while it has none) and due dates, under the heading the server
 * decides, Overdue first and then by the day its current window ends (a final or frozen state, which waits on no window,
 * last), so the Cyber Manager works in the right order. The Systems come from the one dashboard reader
 * ({@link DashboardSystems}) for {@link Need#REVIEW}, with the Cyber Manager's {@link ReviewMarkers}. A System in a
 * Quarter Handled outside the app is listed apart, with nothing to do.
 */
@Service
public class CmInbox {

    /**
     * @param quarter 1 to 4
     * @param submissionId the VA Submission once it is started, otherwise null
     * @param submitDue the last day of the Submission Window (a business date in Asia/Singapore)
     * @param reviewDue the last day of the Review Window
     * @param endorseDue the Quarter's last day
     * @param dueOn the last day of the window its state waits on; null for a final or frozen state
     * @param overdue whether that window passed without the next step; nothing locks
     * @param confirmed the Findings whose current proposal the Cyber Manager confirmed
     * @param awaitingReview the Findings whose current proposal awaits the Cyber Manager's review
     * @param sodConflict whether segregation of duties refuses the caller's review (ticket 28): they acted on the VA
     *     Submission at another step, or are assigned to another step of its System (its SMO/Tech Lead, say)
     * @param exemptionLapsed whether a conditional Exemption of it lapsed (ticket 42)
     */
    public record Item(
            UUID id, String systemId, String name, int quarter, SystemState state, @Nullable UUID submissionId,
            LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue, @Nullable LocalDate dueOn,
            InboxGroup group, boolean overdue, long confirmed, long awaitingReview, boolean sodConflict,
            boolean exemptionLapsed) {}

    /** @param cycle the VA Cycle's year */
    public record Inbox(int cycle, List<Item> systems, List<HandledOutside> handledOutsideApp) {}

    private static final Comparator<Item> OVERDUE_FIRST_THEN_DUE = Comparator.comparing(Item::overdue).reversed()
            .thenComparing(Item::dueOn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(Item::quarter)
            .thenComparing(Item::systemId);

    private final CycleCalendar calendar;
    private final DashboardSystems systems;
    private final ReviewMarkers markers;

    CmInbox(CycleCalendar calendar, DashboardSystems systems, ReviewMarkers markers) {
        this.calendar = calendar;
        this.systems = systems;
        this.markers = markers;
    }

    /**
     * @param requested the VA Cycle's year, or null for the one running today (before the first live Cycle starts, that
     *     Cycle); a Cycle the app does not run is not found
     */
    @Transactional(readOnly = true)
    public Inbox inbox(@Nullable Integer requested) {
        VaCycle cycle = calendar.running(requested);
        DashboardSystems.Rows rows = systems.in(cycle, Need.REVIEW);
        Map<UUID, ReviewMarkers.Marker> marked = markers.of(rows.live(), Need.REVIEW);
        List<Item> items = rows.live().stream()
                .map(row -> item(row, ReviewMarkers.of(marked, row)))
                .sorted(OVERDUE_FIRST_THEN_DUE)
                .toList();
        return new Inbox(cycle.year(), items, rows.handledOutside());
    }

    private static Item item(DashboardSystems.Row row, ReviewMarkers.Marker marker) {
        return new Item(row.system().id(), row.system().systemId(), row.system().name(), row.system().quarter(),
                row.state(), row.submissionId(), row.submitDue(), row.reviewDue(), row.endorseDue(), row.dueOn(),
                group(row.state()), row.overdue(), marker.confirmed(), marker.awaitingReview(), marker.sodConflict(),
                row.exemptionLapsed());
    }

    /** In Review is the Cyber Manager's to act on; a final state is done; anything else waits on someone else. */
    private static InboxGroup group(SystemState state) {
        if (state == SystemState.IN_REVIEW) {
            return InboxGroup.NEEDS_YOUR_ACTION;
        }
        return state.isFinal() ? InboxGroup.DONE : InboxGroup.WAITING_ON_OTHERS;
    }
}
