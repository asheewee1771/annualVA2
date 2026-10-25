package sg.annualva.submission;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.CyclePhase;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;

/**
 * Where every piece of work that waits on a window stands on a business day, for the deadline reminders (ticket 38, ADR
 * 0050), which act for nobody: each VA Submission whose state waits on a window, and each System with none (Not
 * Started), with the last day of that window by the one rule (V20 {@code current_window_due}) and its Overdue flag as
 * the sweep stored it. It looks where the Overdue sweep looks: the active Systems of every VA Cycle that has not closed,
 * in Quarters not Handled outside the app. A retired System, a final state (Endorsed, Exempted, Withdrawn, Not Endorsed)
 * and the frozen Exemption Requested wait on nothing and are left out.
 */
@Service
public class WaitingWork {

    /**
     * One piece of work waiting on a window.
     *
     * @param submissionId null for a System with no VA Submission in the Cycle
     * @param state the state it waits in ({@code NOT_STARTED} for a System with no VA Submission)
     * @param dueOn the last day of the window the state waits on
     * @param overdue whether the Overdue sweep has flagged it
     */
    public record Waiting(UUID systemId, @Nullable UUID submissionId, SubmissionState state, LocalDate dueOn,
            boolean overdue) {}

    private final SubmissionStore store;
    private final RegisterSystems register;
    private final CycleCalendar calendar;

    WaitingWork(SubmissionStore store, RegisterSystems register, CycleCalendar calendar) {
        this.store = store;
        this.register = register;
        this.calendar = calendar;
    }

    /** Everything waiting on a window on this business date, by Cycle, Quarter and System ID. */
    @Transactional(readOnly = true)
    public List<Waiting> on(LocalDate today) {
        List<Waiting> waiting = new ArrayList<>();
        for (VaCycle cycle : calendar.cyclesOn(today)) {
            if (cycle.phaseOn(today) == CyclePhase.CLOSED) {
                continue;
            }
            List<RegisterSystems.ActiveSystem> members = register.everyActive(cycle.year());
            Map<UUID, SubmissionStore.Row> started = store.inCycle(
                    members.stream().map(RegisterSystems.ActiveSystem::id).toList(), cycle.year()).stream()
                    .collect(Collectors.toMap(SubmissionStore.Row::systemId, Function.identity()));
            for (Quarter quarter : cycle.quarters()) {
                if (!quarter.handledOutsideApp()) {
                    List<UUID> inQuarter = members.stream().filter(system -> system.quarter() == quarter.number())
                            .map(RegisterSystems.ActiveSystem::id).toList();
                    waiting.addAll(inQuarter(quarter, inQuarter, started));
                }
            }
        }
        return waiting;
    }

    private List<Waiting> inQuarter(Quarter quarter, List<UUID> systems, Map<UUID, SubmissionStore.Row> started) {
        List<UUID> notStarted = systems.stream().filter(id -> !started.containsKey(id)).toList();
        LocalDate notStartedDue = Objects.requireNonNull(store.notStartedDueOn(quarter), "Not Started waits on a window");
        Set<UUID> flagged = store.notStartedOverdue(notStarted, quarter.cycle());
        List<Waiting> waiting = new ArrayList<>();
        for (UUID system : systems) {
            SubmissionStore.Row row = started.get(system);
            if (row == null) {
                waiting.add(new Waiting(system, null, SubmissionState.NOT_STARTED, notStartedDue,
                        flagged.contains(system)));
            } else if (row.dueOn() != null) {
                waiting.add(new Waiting(system, row.id(), row.state(), row.dueOn(), row.overdue()));
            }
        }
        return waiting;
    }
}
