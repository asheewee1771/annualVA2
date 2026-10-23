package sg.annualva.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import sg.annualva.access.Need;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;
import sg.annualva.submission.Submissions;

/**
 * The SMO/Tech Lead's inbox (stories 18 and 71): the Systems the caller submits for, in one VA Cycle, each in its
 * Quarter with its state, its three due dates and what is needed next. A System in a Quarter Handled outside the app is
 * listed apart, with nothing to do. Which Systems are the caller's is the access guard's answer (ADR 0013), so nobody
 * sees another person's System.
 *
 * <p>A System with no VA Submission is Not Started and needs the SMO to start it, even before its Quarter begins; its
 * dates are the Quarter's. One with a VA Submission shows its state, the dates the submission was started with and the
 * submission's id, so the screen can open it. Overdue is a flag added by the Overdue ticket; the inbox does not guess it.
 */
@Service
public class SmoInbox {

    /** How a System in a Quarter is shown; the other states arrive with the commands that enter them. */
    public enum State {
        NOT_STARTED,
        DRAFT,
        IN_REVIEW,
        RETURNED_FOR_REWORK
    }

    /** What the caller should do next with a System. */
    public enum NextAction {
        START_SUBMISSION,
        PREPARE_SUBMISSION,
        /** Submitted: the Cyber Manager reviews it by the end of the Review Window. */
        AWAIT_REVIEW
    }

    /** The heading a System is listed under on the screen. */
    public enum Group {
        NEEDS_YOUR_ACTION,
        WAITING_ON_OTHERS,
        DONE
    }

    /**
     * @param quarter 1 to 4
     * @param submissionId the VA Submission's id once it is started, otherwise null
     * @param submitDue the last day of the Submission Window (a business date in Asia/Singapore)
     * @param reviewDue the last day of the Review Window
     * @param endorseDue the Quarter's last day
     * @param nextActionDueOn the day the next action is due
     */
    public record Item(
            UUID id, String systemId, String name, int quarter, State state, @Nullable UUID submissionId,
            LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue, NextAction nextAction,
            LocalDate nextActionDueOn, Group group) {}

    /** A System whose Quarter the manual process still runs: named, with no state, dates or action. */
    public record HandledOutside(UUID id, String systemId, String name, int quarter) {}

    /** @param cycle the VA Cycle's year */
    public record Inbox(int cycle, List<Item> systems, List<HandledOutside> handledOutsideApp) {}

    private static final Comparator<RegisterSystems.ActiveSystem> BY_QUARTER_THEN_ID =
            Comparator.comparingInt(RegisterSystems.ActiveSystem::quarter).thenComparing(RegisterSystems.ActiveSystem::systemId);

    private final CycleCalendar calendar;
    private final RegisterSystems register;
    private final Submissions submissions;

    SmoInbox(CycleCalendar calendar, RegisterSystems register, Submissions submissions) {
        this.calendar = calendar;
        this.register = register;
        this.submissions = submissions;
    }

    /**
     * @param requested the VA Cycle's year, or null for the one running today (before the first live Cycle starts, that
     *     Cycle); a Cycle the app does not run is not found
     */
    public Inbox inbox(@Nullable Integer requested) {
        VaCycle cycle = calendar.running(requested);
        List<RegisterSystems.ActiveSystem> mine = register.active(Need.SUBMIT).stream().sorted(BY_QUARTER_THEN_ID).toList();
        Map<UUID, Submissions.Summary> started =
                submissions.inCycle(mine.stream().map(RegisterSystems.ActiveSystem::id).toList(), cycle.year());
        List<Item> systems = new ArrayList<>();
        List<HandledOutside> outside = new ArrayList<>();
        for (RegisterSystems.ActiveSystem system : mine) {
            Quarter quarter = cycle.quarters().get(system.quarter() - 1);
            if (quarter.handledOutsideApp()) {
                outside.add(new HandledOutside(system.id(), system.systemId(), system.name(), system.quarter()));
            } else {
                Submissions.Summary submission = started.get(system.id());
                systems.add(submission == null ? notStarted(system, quarter) : started(system, submission));
            }
        }
        return new Inbox(cycle.year(), List.copyOf(systems), List.copyOf(outside));
    }

    private static Item notStarted(RegisterSystems.ActiveSystem system, Quarter quarter) {
        return new Item(system.id(), system.systemId(), system.name(), system.quarter(), State.NOT_STARTED, null,
                quarter.submitDue(), quarter.reviewDue(), quarter.endorseDue(), NextAction.START_SUBMISSION,
                quarter.submitDue(), Group.NEEDS_YOUR_ACTION);
    }

    /**
     * A Draft, and one Returned for Rework, needs the SMO to prepare and submit it by the Submission Window's end (the
     * rework queue of ticket 31 may date rework otherwise); one In Review waits on the Cyber Manager until the Review
     * Window's end.
     */
    private static Item started(RegisterSystems.ActiveSystem system, Submissions.Summary submission) {
        return switch (submission.state()) {
            case DRAFT -> item(system, submission, State.DRAFT, NextAction.PREPARE_SUBMISSION, submission.submitDue(),
                    Group.NEEDS_YOUR_ACTION);
            case RETURNED_FOR_REWORK -> item(system, submission, State.RETURNED_FOR_REWORK,
                    NextAction.PREPARE_SUBMISSION, submission.submitDue(), Group.NEEDS_YOUR_ACTION);
            case IN_REVIEW -> item(system, submission, State.IN_REVIEW, NextAction.AWAIT_REVIEW, submission.reviewDue(),
                    Group.WAITING_ON_OTHERS);
        };
    }

    private static Item item(RegisterSystems.ActiveSystem system, Submissions.Summary submission, State state,
            NextAction next, LocalDate dueOn, Group group) {
        return new Item(system.id(), system.systemId(), system.name(), system.quarter(), state, submission.id(),
                submission.submitDue(), submission.reviewDue(), submission.endorseDue(), next, dueOn, group);
    }
}
