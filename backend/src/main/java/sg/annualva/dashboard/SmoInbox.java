package sg.annualva.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.Need;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.VaCycle;

/**
 * The SMO/Tech Lead's inbox (stories 18 and 71): the Systems the caller submits for, in one VA Cycle, each in its
 * Quarter with its state, its three due dates and what is needed next. A System in a Quarter Handled outside the app is
 * listed apart, with nothing to do. The Systems come from the one dashboard reader ({@link DashboardSystems}) for
 * {@link Need#SUBMIT}, so nobody sees another person's System (ADR 0013).
 *
 * <p>A System with no VA Submission is Not Started and needs the SMO to start it, even before its Quarter begins; its
 * dates are the Quarter's. One with a VA Submission shows its state, the dates the submission was started with and the
 * submission's id, so the screen can open it. The next action is due on the last day of the window the state waits on, by
 * the one Overdue rule (ADR 0033 item 2), so the date always agrees with the Overdue flag. Overdue is the flag the
 * Overdue sweep stored, on the VA Submission or on the Not Started System; the inbox does not work it out from the dates.
 */
@Service
public class SmoInbox {

    /** What the caller should do next with a System; a final state has nothing next. */
    public enum NextAction {
        START_SUBMISSION,
        PREPARE_SUBMISSION,
        /** Submitted: the Cyber Manager reviews it by the end of the Review Window. */
        AWAIT_REVIEW,
        /** Reviewed: a VA POC endorses it by the Quarter's last day. */
        AWAIT_ENDORSEMENT,
        /** Frozen while a VA POC decides its Exemption; no window runs. */
        AWAIT_EXEMPTION_DECISION
    }

    /**
     * @param quarter 1 to 4
     * @param submissionId the VA Submission's id once it is started, otherwise null
     * @param submitDue the last day of the Submission Window (a business date in Asia/Singapore)
     * @param reviewDue the last day of the Review Window
     * @param endorseDue the Quarter's last day
     * @param nextAction what is next; null once it is in a final state
     * @param nextActionDueOn the last day of the window the state waits on; null when no window runs (frozen, or final)
     * @param overdue whether its window passed without the next step; nothing locks
     * @param exemptionLapsed whether a conditional Exemption of it lapsed, its VA not recorded in time (ticket 42)
     */
    public record Item(
            UUID id, String systemId, String name, int quarter, SystemState state, @Nullable UUID submissionId,
            LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue, @Nullable NextAction nextAction,
            @Nullable LocalDate nextActionDueOn, InboxGroup group, boolean overdue, boolean exemptionLapsed) {}

    /** @param cycle the VA Cycle's year */
    public record Inbox(int cycle, List<Item> systems, List<HandledOutside> handledOutsideApp) {}

    private final CycleCalendar calendar;
    private final DashboardSystems systems;

    SmoInbox(CycleCalendar calendar, DashboardSystems systems) {
        this.calendar = calendar;
        this.systems = systems;
    }

    /**
     * @param requested the VA Cycle's year, or null for the one running today (before the first live Cycle starts, that
     *     Cycle); a Cycle the app does not run is not found
     */
    @Transactional(readOnly = true)
    public Inbox inbox(@Nullable Integer requested) {
        VaCycle cycle = calendar.running(requested);
        DashboardSystems.Rows rows = systems.in(cycle, Need.SUBMIT);
        return new Inbox(cycle.year(), rows.live().stream().map(SmoInbox::item).toList(), rows.handledOutside());
    }

    private static Item item(DashboardSystems.Row row) {
        NextAction next = nextAction(row.state());
        return new Item(row.system().id(), row.system().systemId(), row.system().name(), row.system().quarter(),
                row.state(), row.submissionId(), row.submitDue(), row.reviewDue(), row.endorseDue(), next, row.dueOn(),
                group(next), row.overdue(), row.exemptionLapsed());
    }

    /**
     * Not Started needs the SMO to start it; a Draft, and one Returned for Rework, needs them to prepare and submit it;
     * In Review waits on the Cyber Manager, Awaiting Endorsement on the VA POCs and Exemption Requested on their decision.
     * A final state has nothing next.
     */
    private static @Nullable NextAction nextAction(SystemState state) {
        return switch (state) {
            case NOT_STARTED -> NextAction.START_SUBMISSION;
            case DRAFT, RETURNED_FOR_REWORK -> NextAction.PREPARE_SUBMISSION;
            case IN_REVIEW -> NextAction.AWAIT_REVIEW;
            case AWAITING_ENDORSEMENT -> NextAction.AWAIT_ENDORSEMENT;
            case EXEMPTION_REQUESTED -> NextAction.AWAIT_EXEMPTION_DECISION;
            case ENDORSED, EXEMPTED, WITHDRAWN, NOT_ENDORSED -> null;
        };
    }

    /** The SMO acts on starting and preparing; waiting is on someone else; nothing next is done. */
    private static InboxGroup group(@Nullable NextAction next) {
        if (next == null) {
            return InboxGroup.DONE;
        }
        return next == NextAction.START_SUBMISSION || next == NextAction.PREPARE_SUBMISSION
                ? InboxGroup.NEEDS_YOUR_ACTION
                : InboxGroup.WAITING_ON_OTHERS;
    }
}
