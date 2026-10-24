package sg.annualva.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.CyclePhase;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;

/**
 * The Overdue sweep (stories 42 and 71, ADR 0033). Overdue is a flag, not a state: once a window passes without the next
 * step, the sweep flags the VA Submission, or the System in the Register that has none in that VA Cycle (Not Started), and
 * records when, and audits it. The windows are soft, so nothing else changes: an Overdue VA Submission takes every
 * command it took before. A pass flags only what is not flagged yet, so running it again changes nothing more. A Not
 * Started System is due by its Quarter's Submission Window in every VA Cycle the app runs that has not closed; one in a
 * Quarter Handled outside the app, and a retired System, are never flagged, and neither is a VA Submission of a retired
 * System, which nobody acts on until the Register's withdraw (ticket 43). The clock is read once per pass: the instant
 * from the {@code Clock} bean and today's business date (Asia/Singapore) from that instant. The scheduler
 * ({@code OverdueSweepJob}) or a Seam 1 test runs it. The entries carry ids and a fixed state code only, and have no
 * actor: the sweep acts for nobody.
 */
@Service
public class OverdueSweep {

    private final SubmissionStore store;
    private final RegisterSystems register;
    private final CycleCalendar calendar;
    private final AuditLog audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    OverdueSweep(SubmissionStore store, RegisterSystems register, CycleCalendar calendar, AuditLog audit, Clock clock,
            ApplicationEventPublisher events) {
        this.store = store;
        this.register = register;
        this.calendar = calendar;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    /**
     * One pass: flags and audits whatever became Overdue since the last. Whether a window has passed is the store's one
     * Overdue rule (ADR 0033 item 4); this only says which VA Submissions and which Quarters are looked at. A VA
     * Submission is looked at whatever its Cycle's phase, because its windows all end before its Cycle closes and the
     * close (ticket 41) moves what is left to a final state; a Not Started System only in a Cycle that has not closed, by
     * its Quarter in that Cycle (an older Cycle keeps its Systems as they were when a newer one opened, ADR 0044).
     */
    @Transactional
    public void sweep() {
        Instant now = clock.instant();
        LocalDate today = calendar.dateOf(now);
        List<RegisterSystems.ActiveSystem> systems = register.everyActive();
        List<SubmissionStore.Flagged> flagged = new ArrayList<>(
                store.flagOverdue(systems.stream().map(RegisterSystems.ActiveSystem::id).toList(), today, now));
        for (VaCycle cycle : calendar.cyclesOn(today)) {
            if (cycle.phaseOn(today) == CyclePhase.CLOSED) {
                continue;
            }
            List<RegisterSystems.ActiveSystem> members = register.everyActive(cycle.year());
            for (Quarter quarter : cycle.quarters()) {
                if (!quarter.handledOutsideApp()) {
                    List<UUID> inQuarter = members.stream().filter(system -> system.quarter() == quarter.number())
                            .map(RegisterSystems.ActiveSystem::id).toList();
                    flagged.addAll(store.flagNotStarted(quarter, inQuarter, today, now));
                }
            }
        }
        flagged.forEach(flag -> recorded(flag, now));
    }

    /**
     * One entry per flag, about the System and, when there is one, the VA Submission; and an {@link OverdueEvent} for the
     * notification of whoever's step it is, sent after the commit.
     */
    private void recorded(SubmissionStore.Flagged flagged, Instant now) {
        AuditEvent event = AuditEvent.of(AuditAction.SUBMISSION_OVERDUE, AuditOutcome.SUCCESS)
                .onSystem(flagged.systemId()).with(DetailKey.STATE, flagged.state());
        UUID submission = flagged.id();
        audit.record(submission == null ? event : event.onSubmission(submission));
        events.publishEvent(new OverdueEvent(submission, flagged.systemId(), flagged.state(), flagged.dueOn(), now));
    }
}
