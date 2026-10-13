package sg.annualva.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import sg.annualva.access.Need;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The SMO/Tech Lead's inbox (stories 18 and 71): the Systems the caller submits for, in one VA Cycle, each in its
 * Quarter with its state, its three due dates and what is needed next. A System in a Quarter Handled outside the app is
 * listed apart, with nothing to do. Which Systems are the caller's is the access guard's answer (ADR 0013), so nobody
 * sees another person's System.
 *
 * <p>No VA Submission exists yet (the next ticket creates them), so every System is Not Started and needs the SMO to
 * start it, even before its Quarter begins. Overdue is a flag added by the Overdue ticket; the inbox does not guess it.
 */
@Service
public class SmoInbox {

    /** How a System in a Quarter is shown; only Not Started exists until the VA Submission does. */
    public enum State {
        NOT_STARTED
    }

    /** What the caller should do next with a System. */
    public enum NextAction {
        START_SUBMISSION
    }

    /** The heading a System is listed under on the screen. */
    public enum Group {
        NEEDS_YOUR_ACTION,
        WAITING_ON_OTHERS,
        DONE
    }

    /**
     * @param quarter 1 to 4
     * @param submitDue the last day of the Submission Window (a business date in Asia/Singapore)
     * @param reviewDue the last day of the Review Window
     * @param endorseDue the Quarter's last day
     * @param nextActionDueOn the day the next action is due
     */
    public record Item(
            UUID id, String systemId, String name, int quarter, State state, LocalDate submitDue, LocalDate reviewDue,
            LocalDate endorseDue, NextAction nextAction, LocalDate nextActionDueOn, Group group) {}

    /** A System whose Quarter the manual process still runs: named, with no state, dates or action. */
    public record HandledOutside(UUID id, String systemId, String name, int quarter) {}

    /** @param cycle the VA Cycle's year */
    public record Inbox(int cycle, List<Item> systems, List<HandledOutside> handledOutsideApp) {}

    private static final Comparator<RegisterSystems.ActiveSystem> BY_QUARTER_THEN_ID =
            Comparator.comparingInt(RegisterSystems.ActiveSystem::quarter).thenComparing(RegisterSystems.ActiveSystem::systemId);

    private final CycleCalendar calendar;
    private final RegisterSystems register;

    SmoInbox(CycleCalendar calendar, RegisterSystems register) {
        this.calendar = calendar;
        this.register = register;
    }

    /**
     * @param requested the VA Cycle's year, or null for the one running today (before the first live Cycle starts, that
     *     Cycle); a Cycle the app does not run is not found
     */
    public Inbox inbox(@Nullable Integer requested) {
        VaCycle cycle = cycleFor(requested);
        List<Item> systems = new ArrayList<>();
        List<HandledOutside> outside = new ArrayList<>();
        for (RegisterSystems.ActiveSystem system : register.active(Need.SUBMIT).stream().sorted(BY_QUARTER_THEN_ID).toList()) {
            Quarter quarter = cycle.quarters().get(system.quarter() - 1);
            if (quarter.handledOutsideApp()) {
                outside.add(new HandledOutside(system.id(), system.systemId(), system.name(), system.quarter()));
            } else {
                systems.add(notStarted(system, quarter));
            }
        }
        return new Inbox(cycle.year(), List.copyOf(systems), List.copyOf(outside));
    }

    private VaCycle cycleFor(@Nullable Integer requested) {
        List<VaCycle> running = calendar.cycles();
        if (requested == null) {
            return calendar.currentCycle().orElse(running.getFirst());
        }
        return running.stream().filter(cycle -> cycle.year() == requested).findFirst().orElseThrow(ResourceNotFoundException::new);
    }

    private static Item notStarted(RegisterSystems.ActiveSystem system, Quarter quarter) {
        return new Item(system.id(), system.systemId(), system.name(), system.quarter(), State.NOT_STARTED,
                quarter.submitDue(), quarter.reviewDue(), quarter.endorseDue(), NextAction.START_SUBMISSION,
                quarter.submitDue(), Group.NEEDS_YOUR_ACTION);
    }
}
