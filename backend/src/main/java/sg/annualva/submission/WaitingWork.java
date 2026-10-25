package sg.annualva.submission;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.cycle.CycleCalendar;

/**
 * Where every piece of work that waits on a window stands on a business day, for the deadline reminders (ticket 38, ADR
 * 0051), which act for nobody: each VA Submission whose state waits on a window, and each System with none (Not
 * Started), with the step whose person acts next, the last day of that window by the one rule (V20
 * {@code current_window_due}) and the business day the Overdue sweep flagged it. It looks where the sweep looks
 * ({@link OpenQuarters}), so a retired System, a Quarter Handled outside the app and a closed VA Cycle are never answered;
 * nor are the final states and the frozen Exemption Requested, which wait on no window. It skips the access guard, so it
 * is never for a request: the architecture test names its caller.
 */
@Service
public class WaitingWork {

    /**
     * One piece of work waiting on a window.
     *
     * @param submissionId null for a System with no VA Submission in the Cycle (Not Started)
     * @param state the state it waits in ({@code NOT_STARTED} for a System with no VA Submission)
     * @param step the step whose person acts next ({@link SubmissionState#nextStep})
     * @param dueOn the last day of the window the state waits on
     * @param overdueOn the business day the Overdue sweep flagged it; null while it is not flagged
     */
    public record Waiting(UUID systemId, @Nullable UUID submissionId, SubmissionState state, SubmissionStep step,
            LocalDate dueOn, @Nullable LocalDate overdueOn) {

        /** The VA Submission, which every step after submitting has. */
        public UUID started() {
            return Objects.requireNonNull(submissionId, "only a System waiting to submit has no VA Submission");
        }
    }

    private final SubmissionStore store;
    private final OpenQuarters openQuarters;
    private final CycleCalendar calendar;

    WaitingWork(SubmissionStore store, OpenQuarters openQuarters, CycleCalendar calendar) {
        this.store = store;
        this.openQuarters = openQuarters;
        this.calendar = calendar;
    }

    /** Everything waiting on a window on this business date, by Cycle, Quarter and System ID. */
    @Transactional(readOnly = true)
    public List<Waiting> on(LocalDate today) {
        List<Waiting> waiting = new ArrayList<>();
        for (OpenQuarters.InQuarter open : openQuarters.on(today)) {
            int cycle = open.quarter().cycle();
            Map<UUID, SubmissionStore.Row> started = store.inCycle(open.systems(), cycle).stream()
                    .collect(Collectors.toMap(SubmissionStore.Row::systemId, Function.identity()));
            LocalDate notStartedDue = Objects.requireNonNull(store.notStartedDueOn(open.quarter()),
                    "Not Started waits on the Submission Window");
            Map<UUID, Instant> flagged = store.notStartedOverdueSince(
                    open.systems().stream().filter(id -> !started.containsKey(id)).toList(), cycle);
            for (UUID system : open.systems()) {
                SubmissionStore.Row row = started.get(system);
                if (row == null) {
                    waiting.add(new Waiting(system, null, SubmissionState.NOT_STARTED, SubmissionStep.SUBMIT,
                            notStartedDue, businessDay(flagged.get(system))));
                } else if (row.dueOn() != null) {
                    // A state with a window has a next step (V20 and nextStep name the same five states).
                    waiting.add(new Waiting(system, row.id(), row.state(), row.state().nextStep().orElseThrow(),
                            row.dueOn(), businessDay(row.overdueSince())));
                }
            }
        }
        return waiting;
    }

    /**
     * Whether the work is still where it was read, holding its VA Submission against any move until the caller's
     * transaction ends: a reminder written in that transaction is then never about a state already left, and a VA POC
     * pair it opens is written before a decision's commit clears the pairs of that VA Submission. A System with no VA
     * Submission is taken as still waiting: a reminder that crosses its start is harmless.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean stillWaiting(Waiting waiting) {
        UUID id = waiting.submissionId();
        return id == null || store.stateHeld(id).filter(waiting.state()::equals).isPresent();
    }

    private @Nullable LocalDate businessDay(@Nullable Instant at) {
        return at == null ? null : calendar.dateOf(at);
    }
}
