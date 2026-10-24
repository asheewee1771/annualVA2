package sg.annualva.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.StepUp;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CyclePhase;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.CycleStore;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The VA Cycle lifecycle (stories 17 and 68, ADR 0044). A Cycle opens when its Q1 starts: the lifecycle job records it,
 * and the older Cycle keeps its Systems and Quarters from then on, so a Register apply changes only the newer one. A
 * Cycle closes 4 weeks after its Q4 ends, when the job records the close the day after its close date, or earlier when a
 * VA POC closes it, after a fresh sign-in, once its Q4 has ended. Either way, in one transaction, every VA Submission of
 * the Cycle in a Quarter the app runs that is neither Endorsed, Exempted nor Withdrawn becomes Not Endorsed (Exemption
 * Requested included), every System of it with none gets a Not Endorsed one, each change is audited, the close is
 * recorded and audited, and one {@link CycleClosed} event is published for the SMOs' notifications. VA Submissions of
 * retired Systems are left to the Register's withdraw (reviewer decisions, 2026-10-23), and Quarters Handled outside the
 * app are never Not Endorsed. The clock is read once per pass or close.
 */
@Service
public class CycleLifecycle {

    /** What a close answers: the Cycle's last open day, when it closed, and how many it made Not Endorsed. */
    public record Closed(int year, LocalDate closesOn, Instant closedAt, int notEndorsed) {}

    /** The fixed code of the close step-up (IM8 ac-2): the intent a fresh sign-in is started for. */
    static final String CLOSE_INTENT = "cycle-close";

    private static final String NOT_ALLOWED_NOW = "invalid-transition";

    private final SubmissionStore store;
    private final CycleCalendar calendar;
    private final CycleStore records;
    private final RegisterSystems register;
    private final AuditLog audit;
    private final AuditActor actor;
    private final CurrentUser user;
    private final StepUp stepUp;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CycleLifecycle(SubmissionStore store, CycleCalendar calendar, CycleStore records, RegisterSystems register,
            AuditLog audit, AuditActor actor, CurrentUser user, StepUp stepUp, ApplicationEventPublisher events,
            Clock clock) {
        this.store = store;
        this.calendar = calendar;
        this.records = records;
        this.register = register;
        this.audit = audit;
        this.actor = actor;
        this.user = user;
        this.stepUp = stepUp;
        this.events = events;
        this.clock = clock;
    }

    /**
     * One pass of the lifecycle job: records the opening of every Cycle whose Q1 has started, keeps the older Cycles'
     * Systems, and closes every Cycle past its close date that is not closed yet, for nobody. Running it again changes
     * nothing more.
     */
    @Transactional
    public void run() {
        Instant now = clock.instant();
        LocalDate today = calendar.dateOf(now);
        List<VaCycle> cycles = calendar.cyclesOn(today);
        open(cycles, today, now);
        for (VaCycle cycle : cycles) {
            if (cycle.closedAt() == null && today.isAfter(cycle.closesOn())) {
                store.lockCycleForClose(cycle.year());
                VaCycle held = calendar.cycle(cycle.year()).orElseThrow();
                if (held.closedAt() == null) {
                    closeNow(held, null, null, now, today);
                }
            }
        }
    }

    /**
     * Closes a VA Cycle as a VA POC (stories 17 and 68). A step-up action (IM8 ac-2), so it is refused first unless the
     * caller signed in again within the freshness window (403 {@code step-up-required}). A Cycle the app does not run is
     * "not found". Then, with the Cycle held against every start and every other close, it is refused while its Q4 is
     * still running ({@code cycle-running}) or once it is closed ({@code cycle-closed}), each 409
     * {@code invalid-transition} audited in an entry of its own. Closed before its close date, the Cycle's last open day
     * becomes today.
     *
     * @throws ResourceNotFoundException for a year the app does not run
     * @throws RejectedRequestException 409 {@code invalid-transition}, as above
     */
    @Transactional
    public Closed close(int year) {
        stepUp.require(CLOSE_INTENT);
        calendar.running(year);
        Instant now = clock.instant();
        LocalDate today = calendar.dateOf(now);
        open(calendar.cyclesOn(today), today, now);
        store.lockCycleForClose(year);
        VaCycle cycle = calendar.running(year);
        if (cycle.closedAt() != null) {
            throw refusedClose(year, "cycle-closed", "This VA Cycle is already closed.");
        }
        if (!today.isAfter(cycle.endsOn())) {
            throw refusedClose(year, "cycle-running",
                    "This VA Cycle can be closed only after its Q4 ends, while late endorsements still have time.");
        }
        return closeNow(cycle, user.id(), today.isAfter(cycle.closesOn()) ? null : today, now, today);
    }

    /**
     * Records the opening of every Cycle whose Q1 has started (audited once each, for nobody) and keeps the Systems of every
     * Cycle that a newer one has followed. Both are idempotent.
     */
    private void open(List<VaCycle> cycles, LocalDate today, Instant now) {
        for (VaCycle cycle : cycles) {
            if (cycle.phaseOn(today) != CyclePhase.UPCOMING && records.open(cycle.year(), now)) {
                audit.record(AuditEvent.of(AuditAction.CYCLE_OPENED, AuditOutcome.SUCCESS)
                        .with(DetailKey.CYCLE, cycle.year()));
            }
        }
        register.keepOlderCycles(now);
    }

    /**
     * The close itself, for a Cycle the caller holds ({@link SubmissionStore#lockCycleForClose}) and has opened.
     *
     * @param by the VA POC who closes it, to whom every entry is attributed; null for the lifecycle job
     * @param closesOn an early close's date; null to keep the Cycle's own
     */
    private Closed closeNow(VaCycle cycle, @Nullable UUID by, @Nullable LocalDate closesOn, Instant now,
            LocalDate today) {
        int year = cycle.year();
        Integer firstLive = cycle.firstLiveQuarter();
        List<RegisterSystems.ActiveSystem> members = register.everyActive(year);
        List<SubmissionStore.NotEndorsed> moved = new ArrayList<>();
        if (firstLive != null) {
            moved.addAll(store.notEndorseUnfinished(year, firstLive,
                    register.everyActive().stream().map(RegisterSystems.ActiveSystem::id).toList(), today));
            for (Quarter quarter : cycle.quarters()) {
                if (!quarter.handledOutsideApp()) {
                    moved.addAll(store.notEndorseNotStarted(quarter, members.stream()
                            .filter(system -> system.quarter() == quarter.number())
                            .map(RegisterSystems.ActiveSystem::id).toList()));
                }
            }
        }
        records.close(year, closesOn, now, by);
        List<CycleClosed.NotEndorsed> notEndorsed = new ArrayList<>();
        for (SubmissionStore.NotEndorsed item : moved) {
            String from = item.from() == null ? SubmissionStore.NOT_STARTED : item.from();
            audit.record(attributed(AuditEvent.of(AuditAction.SUBMISSION_NOT_ENDORSED, AuditOutcome.SUCCESS), by)
                    .onSystem(item.systemId()).onSubmission(item.id())
                    .with(DetailKey.STATE_FROM, from)
                    .with(DetailKey.STATE_TO, SubmissionState.NOT_ENDORSED.name())
                    .with(DetailKey.CYCLE, year));
            notEndorsed.add(new CycleClosed.NotEndorsed(item.id(), item.systemId(),
                    item.from() == null ? null : SubmissionState.valueOf(item.from())));
        }
        audit.record(attributed(AuditEvent.of(AuditAction.CYCLE_CLOSED, AuditOutcome.SUCCESS), by)
                .with(DetailKey.CYCLE, year).with(DetailKey.COUNT, notEndorsed.size()));
        events.publishEvent(new CycleClosed(year, by, now, notEndorsed));
        return new Closed(year, closesOn == null ? cycle.closesOn() : closesOn, now, notEndorsed.size());
    }

    /** Attributed to the VA POC who closes, or to nobody for the job. */
    private AuditEvent attributed(AuditEvent event, @Nullable UUID by) {
        return by == null ? event : actor.attribute(event);
    }

    /** Audits a refused close in a transaction of its own (ADR 0014), then answers 409; reason and sentence are fixed. */
    private RejectedRequestException refusedClose(int year, String reason, String detail) {
        audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.CYCLE_CLOSED, AuditOutcome.REFUSED))
                .with(DetailKey.CYCLE, year).with(DetailKey.REASON, reason));
        return new RejectedRequestException(HttpStatus.CONFLICT, NOT_ALLOWED_NOW, detail);
    }
}
