package sg.annualva.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 * The conditional Exemption lapse (story 47, ADR 0051). A conditional Exemption whose VA its SMO/Tech Lead has not
 * recorded by its lapse date (the Quarter's last day plus 3 months) lapses the next day: while its VA Cycle is open the
 * VA Submission goes back to the state the request froze (Not Started when the request created it), flagged as lapsed,
 * with a Submission Window ending 13 days from today, a Review Window 14 days after that, and the endorsement due by the
 * later of the Quarter's last day and the Cycle's close, a window that would end on or after the next one's last day
 * being cut to end the day before it; once the Cycle has closed it becomes Not Endorsed and keeps its
 * windows (spec: a Q4 conditional Exemption resolves after the close, the one change allowed to a closed Cycle). Each
 * lapse is audited, for nobody, and published for the notifications. A pass lapses only what has not lapsed, so running
 * it again changes nothing more (as the Overdue sweep, ADR 0033); a VA Submission whose row a record or a confirmation
 * holds is skipped until the next pass. VA Submissions of retired Systems are left to the Register's withdraw, as every
 * job leaves them. The clock is read once per pass. The scheduler ({@code ExemptionLapseJob}) or a Seam 1 test runs it.
 */
@Service
public class ExemptionLapse {

    /** The new Submission Window ends this many days after the day the lapse is flagged: two weeks, that day included. */
    static final int SUBMIT_DAYS = 13;

    /** The new Review Window ends two weeks after the new Submission Window. */
    static final int REVIEW_DAYS = 14;

    private final ExemptionStore exemptions;
    private final SubmissionStore store;
    private final RegisterSystems register;
    private final CycleCalendar calendar;
    private final AuditLog audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    ExemptionLapse(ExemptionStore exemptions, SubmissionStore store, RegisterSystems register, CycleCalendar calendar,
            AuditLog audit, Clock clock, ApplicationEventPublisher events) {
        this.exemptions = exemptions;
        this.store = store;
        this.register = register;
        this.calendar = calendar;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    /**
     * One pass. Every Cycle the app runs is held shared first, in year order, so no close runs while the pass decides
     * whether a Cycle is open and moves its VA Submissions (the locks in the order ADR 0044 item 6 sets: the Cycle, then
     * the rows); each Cycle's phase is then read again with its close as recorded.
     */
    @Transactional
    public void run() {
        Instant now = clock.instant();
        LocalDate today = calendar.dateOf(now);
        List<VaCycle> cycles = calendar.cyclesOn(today);
        cycles.forEach(cycle -> store.holdCycle(cycle.year()));
        List<UUID> active = register.everyActive().stream().map(RegisterSystems.ActiveSystem::id).toList();
        for (VaCycle listed : cycles) {
            VaCycle cycle = calendar.cycle(listed.year()).orElseThrow();
            boolean closed = cycle.phaseOn(today) == CyclePhase.CLOSED;
            for (ExemptionStore.Lapsing due : exemptions.lapsing(cycle.year(), active, today)) {
                lapse(due, closed ? null : cycle, today, now);
            }
        }
    }

    /**
     * Lapses one conditional Exemption: back to the state the request froze with new windows in an open Cycle, or Not
     * Endorsed when {@code openCycle} is null (closed).
     */
    private void lapse(ExemptionStore.Lapsing due, @Nullable VaCycle openCycle, LocalDate today, Instant now) {
        UUID id = due.submissionId();
        if (!exemptions.lapsed(due.exemptionId(), now)) {
            return; // Recorded or lapsed meanwhile: the claim holds the row, so this guards a change of locking only.
        }
        SubmissionState to;
        if (openCycle == null) {
            to = SubmissionState.NOT_ENDORSED;
            store.moved(id, to, today);
        } else {
            to = due.previousState();
            Quarter quarter = openCycle.quarters().get(due.quarter() - 1);
            LocalDate endorseDue = later(quarter.endsOn(), openCycle.closesOn());
            // The windows end in order, as V8 checks, so ones that would run past the endorsement are cut to end the
            // day before it (ADR 0051 item 5): the Cycle's close ends every window anyway.
            LocalDate reviewDue = earlier(today.plusDays(SUBMIT_DAYS + REVIEW_DAYS), endorseDue.minusDays(1));
            LocalDate submitDue = earlier(today.plusDays(SUBMIT_DAYS), reviewDue.minusDays(1));
            store.lapsedBack(id, to, submitDue, reviewDue, endorseDue, today);
        }
        audit.record(AuditEvent.of(AuditAction.EXEMPTION_LAPSED, AuditOutcome.SUCCESS).onSystem(due.systemId())
                .onSubmission(id)
                .with(DetailKey.STATE_FROM, SubmissionState.EXEMPTED.name())
                .with(DetailKey.STATE_TO, to.name())
                .with(DetailKey.EXEMPTION_ID, due.exemptionId()));
        LocalDate dueOn = store.find(id).map(SubmissionStore.Row::dueOn).orElse(null);
        events.publishEvent(new ExemptionEvent(ExemptionEvent.Kind.LAPSED, due.exemptionId(), id, due.systemId(), to,
                dueOn, null, now));
    }

    private static LocalDate later(LocalDate one, LocalDate other) {
        return one.isAfter(other) ? one : other;
    }

    private static LocalDate earlier(LocalDate one, LocalDate other) {
        return one.isBefore(other) ? one : other;
    }
}
