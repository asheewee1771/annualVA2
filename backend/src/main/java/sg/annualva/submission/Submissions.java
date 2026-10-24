package sg.annualva.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.Need;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.register.RegisterSystems;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The VA Submission (stories 19, 40 and 41). An SMO/Tech Lead starts the one for a System in a VA Cycle: it is created in
 * Draft with the Submission, Review and Endorsement due dates taken from the System's Quarter, and the caller is recorded
 * at the submitting step, the first fact segregation of duties rests on. Starting is allowed before the Quarter begins. A
 * System has one VA Submission per Cycle, so a second start is refused (409 {@code invalid-transition}), as is the start
 * of a System whose Quarter is Handled outside the app. Once nothing stands in the way and the SMO/Tech Lead has accepted
 * the Declaration, they submit it for review ({@link #submit}). The windows are soft: a VA Submission the Overdue sweep
 * flagged ({@link OverdueSweep}) takes every command it took before. Each command checks, in this order: ownership (the access
 * guard, so a System the caller may not reach is "not found", ADR 0013), the Cycle or the state, then the preconditions,
 * then writes, records the step and audits, all in one transaction. A refusal is audited as well, in a transaction of its
 * own (ADR 0014), because the command's own rolls back.
 */
@Service
public class Submissions {

    /** The step a person acted at on a VA Submission, and when they first did. The person is an id, never a name or email. */
    public record Acted(SubmissionStep step, UUID userId, Instant at) {}

    /**
     * The Declaration as the SMO/Tech Lead accepted it at a submit: the exact wording of its version, kept with the VA
     * Submission (story 41).
     *
     * @param acceptedBy who accepted it (an id, never a name or email)
     * @param acceptedAt when (UTC)
     */
    public record AcceptedDeclaration(int version, String text, UUID acceptedBy, Instant acceptedAt) {}

    /**
     * A VA Submission as anyone who may see its System reads it. Dates are business dates (Asia/Singapore).
     *
     * @param cycle the VA Cycle's year
     * @param quarter 1 to 4
     * @param overdue the Overdue flag: its current window passed without the next step (ADR 0033); nothing locks
     * @param endorsedLate whether it was endorsed after the Quarter's last day
     * @param startedAt when it was started (UTC)
     * @param submittedAt when it was last submitted for review (UTC); null until it is
     * @param overdueSince when the Overdue flag was set (UTC); null while it is clear
     * @param declaration the Declaration accepted at its latest submit; null until it is submitted
     * @param acted who acted at which step, in the order they first did
     */
    public record Submission(
            UUID id, UUID systemId, int cycle, int quarter, SubmissionState state, boolean overdue, boolean endorsedLate,
            LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue, Instant startedAt,
            @Nullable Instant submittedAt, @Nullable Instant overdueSince, @Nullable AcceptedDeclaration declaration,
            List<Acted> acted) {}

    /**
     * A VA Submission someone may see, for a read of what hangs off it (its files, Findings and Scan Reports). It grants
     * nothing to write.
     *
     * @param systemId the System the VA Submission belongs to
     */
    public record ReadTarget(UUID submissionId, UUID systemId) {}

    /**
     * A VA Submission whose prepared content the caller may change now: its SMO/Tech Lead calls, it is in Draft or Returned
     * for Rework, and its row is held shared until the caller's transaction ends, so no submit can move it in between (ADR
     * 0032 item 5). Only {@link #lockForEdit} makes one, and every write to what the SMO/Tech Lead prepares (a file, a
     * Finding, a proposal, a scan date, a Scan Report's mode) takes one, so no such write can skip the lock or the state.
     */
    public static final class EditTarget {

        private final UUID submissionId;
        private final UUID systemId;

        private EditTarget(UUID submissionId, UUID systemId) {
            this.submissionId = submissionId;
            this.systemId = systemId;
        }

        public UUID submissionId() {
            return submissionId;
        }

        /** The System the VA Submission belongs to. */
        public UUID systemId() {
            return systemId;
        }
    }

    /**
     * What a write answers when the VA Submission is not in Draft or Returned for Rework: it audits its own refusal (ADR
     * 0014) and returns the 409 to throw.
     */
    @FunctionalInterface
    public interface Refusal {
        RuntimeException refuse(UUID submissionId, UUID systemId);
    }

    /**
     * What a dashboard needs of a started VA Submission.
     *
     * @param overdue the Overdue flag (ADR 0033)
     * @param dueOn the last day of the window its state waits on, by the one Overdue rule (ADR 0033 item 2); null for a
     *     final or frozen state, which waits on none
     */
    public record Summary(
            UUID id, SubmissionState state, LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue,
            boolean overdue, @Nullable LocalDate dueOn) {}

    /**
     * What the SMO/Tech Lead says about the Declaration when submitting.
     *
     * @param declarationAccepted whether they ticked it; null is not ticked
     * @param declarationVersion the version they were shown; it must be the current one, so what is stored is what they read
     */
    public record DeclarationAnswer(@Nullable Boolean declarationAccepted, @Nullable Integer declarationVersion) {}

    private static final String NOT_ALLOWED_NOW = "invalid-transition";

    private final SubmissionStore store;
    private final RegisterSystems register;
    private final CycleCalendar calendar;
    private final AccessGuard guard;
    private final CurrentUser user;
    private final AuditActor actor;
    private final AuditLog audit;
    private final Clock clock;
    private final SubmitReadiness readiness;
    private final ApplicationEventPublisher events;

    Submissions(SubmissionStore store, RegisterSystems register, CycleCalendar calendar, AccessGuard guard,
            CurrentUser user, AuditActor actor, AuditLog audit, Clock clock, SubmitReadiness readiness,
            ApplicationEventPublisher events) {
        this.store = store;
        this.register = register;
        this.calendar = calendar;
        this.guard = guard;
        this.user = user;
        this.actor = actor;
        this.audit = audit;
        this.clock = clock;
        this.readiness = readiness;
        this.events = events;
    }

    /**
     * Starts the VA Submission for a System the caller is SMO/Tech Lead of.
     *
     * @param cycleYear the VA Cycle's year; null for the one running today (before the first live Cycle starts, that
     *     Cycle)
     * @throws ResourceNotFoundException for a System the caller may not submit for, or a Cycle the app does not run
     * @throws RejectedRequestException 409 {@code invalid-transition} when the System already has a VA Submission in the
     *     Cycle, or its Quarter is Handled outside the app
     */
    @Transactional
    public Submission start(UUID systemId, @Nullable Integer cycleYear) {
        RegisterSystems.ActiveSystem system = register.active(systemId, Need.SUBMIT);
        VaCycle cycle = calendar.running(cycleYear);
        Quarter quarter = cycle.quarters().get(system.quarter() - 1);
        if (quarter.handledOutsideApp()) {
            throw refusedStart(systemId, "quarter-handled-outside-app",
                    "This System's Quarter is handled outside the app, so no VA Submission is started for it here.");
        }
        Instant now = clock.instant();
        UUID caller = user.id();
        UUID id = store.insert(systemId, cycle.year(), quarter.number(), SubmissionState.DRAFT, quarter.submitDue(),
                quarter.reviewDue(), quarter.endorseDue(), now, calendar.dateOf(now), caller).orElseThrow(() -> refusedStart(systemId,
                        "already-started", "This System already has a VA Submission in this VA Cycle."));
        store.recordParticipation(id, caller, SubmissionStep.SUBMIT, now);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_STARTED, AuditOutcome.SUCCESS))
                .onSystem(systemId).onSubmission(id).with(DetailKey.STATE_TO, SubmissionState.DRAFT.name()));
        return view(id);
    }

    /**
     * Submits a VA Submission of a System the caller is SMO/Tech Lead of for review (stories 40 and 41). The checks run in
     * the order ownership (another System's VA Submission is "not found"), then, with the VA Submission's row held against
     * every write to what the SMO/Tech Lead prepares, its state (Draft or Returned for Rework), then everything that
     * stands in the way ({@link SubmitReadiness}) and the Declaration, all listed in one refusal. Holding the row first
     * means no proposal, upload, scan-date confirmation or mode change can land between the checks and the move: each takes
     * the row shared ({@link #lockForEdit}). On success the wording of the Declaration version accepted is kept with who
     * accepted it and when, the VA Submission moves to In Review, the caller is recorded at the submitting step, the submit
     * is audited and a {@link SubmissionEvent} is published.
     *
     * @throws ResourceNotFoundException for a VA Submission that does not exist or of a System the caller is not SMO/Tech
     *     Lead of
     * @throws RejectedRequestException 409 {@code invalid-transition} when it is not in Draft or Returned for Rework; 409
     *     {@code not-ready} listing every {@link SubmitReadiness.Blocker} as {@code reasons}, the Declaration's included
     */
    @Transactional
    public Submission submit(UUID id, DeclarationAnswer answer) {
        SubmissionStore.Located held = held(id, true, this::notSubmittable);
        UUID systemId = held.systemId();
        SubmissionState from = SubmissionState.valueOf(held.state());
        Declarations.Text declaration = store.currentDeclarationText();
        List<SubmitReadiness.Blocker> blockers = new ArrayList<>(readiness.blockers(id));
        declarationBlocker(answer, declaration.version()).ifPresent(blockers::add);
        if (!blockers.isEmpty()) {
            throw notReady(systemId, id, blockers);
        }
        Instant now = clock.instant();
        UUID caller = user.id();
        store.acceptDeclaration(id, declaration.version(), caller, now);
        store.submitted(id, SubmissionState.IN_REVIEW, caller, now, calendar.dateOf(now));
        store.recordParticipation(id, caller, SubmissionStep.SUBMIT, now);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_SUBMITTED, AuditOutcome.SUCCESS))
                .onSystem(systemId).onSubmission(id)
                .with(DetailKey.STATE_FROM, from.name())
                .with(DetailKey.STATE_TO, SubmissionState.IN_REVIEW.name())
                .with(DetailKey.DECLARATION_VERSION, declaration.version()));
        events.publishEvent(new SubmissionEvent(id, systemId, from, SubmissionState.IN_REVIEW, caller, now));
        return view(id);
    }

    /** The Declaration stands in the way unless the current version was ticked. */
    private static Optional<SubmitReadiness.Blocker> declarationBlocker(DeclarationAnswer answer, int current) {
        if (!Boolean.TRUE.equals(answer.declarationAccepted())) {
            return Optional.of(new SubmitReadiness.Blocker("declaration-not-accepted",
                    "Tick the Declaration to accept it before submitting.", null, List.of()));
        }
        Integer version = answer.declarationVersion();
        if (version == null || version != current) {
            return Optional.of(new SubmitReadiness.Blocker("declaration-changed",
                    "The Declaration has changed since it was shown: read it again and accept it.", null, List.of()));
        }
        return Optional.empty();
    }

    /**
     * A VA Submission, for anyone who may see its System: the System's SMO/Tech Lead or Cyber Manager, or a VA POC.
     * One that does not exist and one the caller may not see are both "not found" (ADR 0013), and neither is audited. A
     * read by anyone but the System's own SMO/Tech Lead (a Cyber Manager or a VA POC) is audited as
     * {@code SUBMISSION_VIEWED}, in the read's transaction, with ids only; the owner's own read writes nothing
     * (reviewer-decisions.md, ADR 0020). The transaction is read-write because the entry is a write.
     */
    @Transactional
    public Submission get(UUID id) {
        Submission submission = view(id);
        if (!guard.scope(Need.SUBMIT).contains(submission.systemId())) {
            audit.record(actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_VIEWED, AuditOutcome.SUCCESS))
                    .onSystem(submission.systemId()).onSubmission(id));
        }
        return submission;
    }

    /**
     * The VA Submission, for a read of what hangs off it, by anyone who may see its System. One the caller may not see is
     * "not found", the same as one that does not exist (ADR 0013). It grants nothing to write: a write takes
     * {@link #lockForEdit}.
     */
    @Transactional(readOnly = true)
    public ReadTarget readTarget(UUID id) {
        SubmissionStore.Located located = store.locate(id).orElseThrow(ResourceNotFoundException::new);
        guard.requireSystem(located.systemId(), Need.VIEW);
        return new ReadTarget(id, located.systemId());
    }

    /**
     * The System a VA Submission belongs to, for a read by a module that keeps something of its own on it (its Findings),
     * by anyone who may see the System; otherwise "not found" (ADR 0013).
     */
    @Transactional(readOnly = true)
    public UUID systemOf(UUID id) {
        return readTarget(id).systemId();
    }

    /**
     * The only way to write to what the SMO/Tech Lead prepares in a VA Submission (a file, a Finding, a proposal, a scan
     * date, a Scan Report's mode). Ownership comes first (a VA Submission of a System the caller is not SMO/Tech Lead of is
     * "not found", ADR 0013), then, with its row held shared until the caller's transaction ends, the state: anything but
     * Draft or Returned for Rework is refused through {@code onRefused}, which audits its own refusal. So a submit cannot
     * move it while the write is under way, and a write that waited for a submit sees the state the submit left (ADR 0032
     * item 5).
     *
     * @throws ResourceNotFoundException as above
     * @throws RuntimeException what {@code onRefused} returns, when the VA Submission takes no changes now
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EditTarget lockForEdit(UUID id, Refusal onRefused) {
        SubmissionStore.Located held = held(id, false, onRefused);
        return new EditTarget(id, held.systemId());
    }

    /**
     * An early check of the same ownership and state, without the lock, for an upload that should be refused before its
     * bytes are quarantined. It grants nothing to write: keeping the file still takes {@link #lockForEdit}, which checks
     * again.
     *
     * @throws ResourceNotFoundException for a VA Submission of a System the caller is not SMO/Tech Lead of
     * @throws RuntimeException what {@code onRefused} returns, when the VA Submission takes no changes now
     */
    @Transactional(readOnly = true)
    public ReadTarget precheckEdit(UUID id, Refusal onRefused) {
        SubmissionStore.Located located = owned(id);
        if (!editable(located.state())) {
            throw onRefused.refuse(id, located.systemId());
        }
        return new ReadTarget(id, located.systemId());
    }

    /** One the SMO/Tech Lead of its System calls about; otherwise "not found". */
    private SubmissionStore.Located owned(UUID id) {
        SubmissionStore.Located located = store.locate(id).orElseThrow(ResourceNotFoundException::new);
        guard.requireSystem(located.systemId(), Need.SUBMIT);
        return located;
    }

    /**
     * The VA Submission of a System the caller is SMO/Tech Lead of, its row held until the transaction ends (exclusively for
     * a submit, shared for any other write) and its state then read: one not in Draft or Returned for Rework is refused.
     */
    private SubmissionStore.Located held(UUID id, boolean forSubmit, Refusal onRefused) {
        UUID systemId = owned(id).systemId();
        SubmissionStore.Located held = (forSubmit ? store.lockForSubmit(id) : store.lockForEdit(id))
                .orElseThrow(ResourceNotFoundException::new);
        if (!editable(held.state())) {
            throw onRefused.refuse(id, systemId);
        }
        return held;
    }

    private Submission view(UUID id) {
        SubmissionStore.Row row = store.find(id).orElseThrow(ResourceNotFoundException::new);
        guard.requireSystem(row.systemId(), Need.VIEW);
        return new Submission(row.id(), row.systemId(), row.cycleYear(), row.quarter(), row.state(), row.overdue(),
                row.endorsedLate(), row.submitDue(), row.reviewDue(), row.endorseDue(), row.startedAt(),
                row.submittedAt(), row.overdueSince(), store.latestDeclaration(id).orElse(null), store.participation(id));
    }

    /**
     * The started VA Submissions among these Systems in one Cycle, by System, for a read model. Only Systems the caller
     * may see are answered; a System with no VA Submission is Not Started and is absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Summary> inCycle(Collection<UUID> systemIds, int cycleYear) {
        var scope = guard.scope(Need.VIEW);
        Map<UUID, Summary> started = new HashMap<>();
        for (SubmissionStore.Row row : store.inCycle(systemIds.stream().filter(scope::contains).toList(), cycleYear)) {
            started.put(row.systemId(),
                    new Summary(row.id(), row.state(), row.submitDue(), row.reviewDue(), row.endorseDue(),
                            row.overdue(), row.dueOn()));
        }
        return started;
    }

    /**
     * The last day of the window a System with no VA Submission (Not Started) in this Quarter waits on, by the one Overdue
     * rule (ADR 0033 item 2), for a read model; the same rule gives a started VA Submission's {@link Summary#dueOn}.
     */
    @Transactional(readOnly = true)
    public @Nullable LocalDate notStartedDueOn(Quarter quarter) {
        return store.notStartedDueOn(quarter);
    }

    /**
     * The Systems among these, in one Cycle, that the Overdue sweep flagged while they had no VA Submission (Not
     * Started), for a read model. Only Systems the caller may see are answered.
     */
    @Transactional(readOnly = true)
    public Set<UUID> overdueNotStarted(Collection<UUID> systemIds, int cycleYear) {
        var scope = guard.scope(Need.VIEW);
        return store.notStartedOverdue(systemIds.stream().filter(scope::contains).toList(), cycleYear);
    }

    /**
     * The VA Submissions among these at which the caller acted at a step other than this one, for a read model that marks
     * where segregation of duties will refuse them that step (story 58): a Cyber Manager who started or submitted a VA
     * Submission as its SMO/Tech Lead may not review it. Only the caller's own record is read.
     */
    @Transactional(readOnly = true)
    public Set<UUID> actedAtAnotherStep(Collection<UUID> submissionIds, SubmissionStep step) {
        return store.actedAtAnotherStep(submissionIds, user.id(), step);
    }

    /**
     * The states in which the SMO/Tech Lead is still preparing the VA Submission (story 20). Read as the table holds them,
     * so a state no constant names yet is simply not editable.
     */
    private static boolean editable(String state) {
        return SubmissionState.DRAFT.name().equals(state) || SubmissionState.RETURNED_FOR_REWORK.name().equals(state);
    }

    /** Audits the refusal in a transaction of its own, then answers 409; the reason and sentence are fixed text. */
    private RejectedRequestException refusedStart(UUID systemId, String reason, String detail) {
        audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_STARTED, AuditOutcome.REFUSED))
                .onSystem(systemId).with(DetailKey.REASON, reason));
        return new RejectedRequestException(HttpStatus.CONFLICT, NOT_ALLOWED_NOW, detail);
    }

    /** A submit of a VA Submission not in Draft or Returned for Rework: audited in a transaction of its own, then 409. */
    private RejectedRequestException notSubmittable(UUID id, UUID systemId) {
        audit.recordIndependently(refusedSubmit(id, systemId, "state-not-submittable"));
        return new RejectedRequestException(HttpStatus.CONFLICT, NOT_ALLOWED_NOW,
                "This VA Submission can be submitted only from Draft or Returned for Rework.");
    }

    /**
     * A submit with something in the way: audited in a transaction of its own with how many reasons there are, then 409
     * {@code not-ready} listing them.
     */
    private RejectedRequestException notReady(UUID systemId, UUID id, List<SubmitReadiness.Blocker> blockers) {
        audit.recordIndependently(refusedSubmit(id, systemId, "not-ready").with(DetailKey.COUNT, blockers.size()));
        return RejectedRequestException.withReasons(HttpStatus.CONFLICT, "not-ready",
                "This VA Submission cannot be submitted yet. Each reason says what to do.", blockers);
    }

    private AuditEvent refusedSubmit(UUID id, UUID systemId, String reason) {
        return actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_SUBMITTED, AuditOutcome.REFUSED))
                .onSystem(systemId).onSubmission(id).with(DetailKey.REASON, reason);
    }
}
