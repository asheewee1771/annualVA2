package sg.annualva.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.Need;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;
import sg.annualva.submission.Submissions;

/**
 * The one reader every dashboard lists its Systems through (ADR 0034 item 1): the active Systems the caller may reach for
 * a need, in one VA Cycle, each with its VA Submission's state, dates and Overdue flag, or Not Started with its Quarter's
 * dates and the flag the sweep set on the System. Which Systems are the caller's is the access guard's answer (ADR
 * 0013). The day each state's window ends comes from the one Overdue rule (ADR 0033 item 2), for Not Started too, and
 * Overdue is the stored flag, never a date compared here. Systems in Quarters Handled outside the app are listed apart.
 */
@Component
class DashboardSystems {

    /**
     * One System in a Quarter the app runs.
     *
     * @param submissionId its VA Submission in the Cycle, or null while it is Not Started
     * @param dueOn the last day of the window its state waits on; null for a final or frozen state
     */
    record Row(
            RegisterSystems.ActiveSystem system, SystemState state, @Nullable UUID submissionId, LocalDate submitDue,
            LocalDate reviewDue, LocalDate endorseDue, @Nullable LocalDate dueOn, boolean overdue) {}

    /** The Systems: those in Quarters the app runs, and apart those Handled outside the app. */
    record Rows(List<Row> live, List<HandledOutside> handledOutside) {}

    private static final Comparator<RegisterSystems.ActiveSystem> BY_QUARTER_THEN_ID =
            Comparator.comparingInt(RegisterSystems.ActiveSystem::quarter).thenComparing(RegisterSystems.ActiveSystem::systemId);

    private final RegisterSystems register;
    private final Submissions submissions;

    DashboardSystems(RegisterSystems register, Submissions submissions) {
        this.register = register;
        this.submissions = submissions;
    }

    /** The caller's Systems for this need in every Quarter of the Cycle, by Quarter then System ID. */
    @Transactional(readOnly = true)
    Rows in(VaCycle cycle, Need need) {
        return rows(cycle, need, null);
    }

    /** The same, in one Quarter only. */
    @Transactional(readOnly = true)
    Rows in(VaCycle cycle, Need need, int quarter) {
        return rows(cycle, need, quarter);
    }

    private Rows rows(VaCycle cycle, Need need, @Nullable Integer onlyQuarter) {
        List<RegisterSystems.ActiveSystem> mine = register.active(need).stream()
                .filter(system -> onlyQuarter == null || system.quarter() == onlyQuarter)
                .sorted(BY_QUARTER_THEN_ID)
                .toList();
        List<UUID> ids = mine.stream().map(RegisterSystems.ActiveSystem::id).toList();
        Map<UUID, Submissions.Summary> started = submissions.inCycle(ids, cycle.year());
        Set<UUID> overdueNotStarted = submissions.overdueNotStarted(ids, cycle.year());
        Map<Integer, LocalDate> notStartedDue = new HashMap<>();
        List<Row> live = new ArrayList<>();
        List<HandledOutside> outside = new ArrayList<>();
        for (RegisterSystems.ActiveSystem system : mine) {
            Quarter quarter = cycle.quarters().get(system.quarter() - 1);
            Submissions.Summary submission = started.get(system.id());
            if (quarter.handledOutsideApp()) {
                outside.add(HandledOutside.of(system));
            } else if (submission == null) {
                LocalDate dueOn = notStartedDue.computeIfAbsent(quarter.number(),
                        number -> submissions.notStartedDueOn(quarter));
                live.add(new Row(system, SystemState.NOT_STARTED, null, quarter.submitDue(), quarter.reviewDue(),
                        quarter.endorseDue(), dueOn, overdueNotStarted.contains(system.id())));
            } else {
                live.add(new Row(system, SystemState.of(submission.state()), submission.id(), submission.submitDue(),
                        submission.reviewDue(), submission.endorseDue(), submission.dueOn(), submission.overdue()));
            }
        }
        return new Rows(List.copyOf(live), List.copyOf(outside));
    }
}
