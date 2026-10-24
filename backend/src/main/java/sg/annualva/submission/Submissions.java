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
import java.util.function.Function;
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
import sg.annualva.access.StepUp;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.Quarter;
import sg.annualva.cycle.VaCycle;
import sg.annualva.parser.ReportText;
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
     * A VA Submission the caller may review now (story 50): they are the Cyber Manager assigned to its System, segregation
     * of duties allows them the reviewing step, it is In Review, and its row is held shared until the caller's transaction
     * ends, so no other command can move it in between. The caller is recorded at the reviewing step when that transaction
     * commits. Only {@link #lockForReview} makes one, and every write of a review takes one.
     */
    public static final class ReviewTarget {

        private final UUID submissionId;
        private final UUID systemId;
        private final AuditEvent refused;

        private ReviewTarget(UUID submissionId, UUID systemId, AuditEvent refused) {
            this.submissionId = submissionId;
            this.systemId = systemId;
            this.refused = refused;
        }

        public UUID submissionId() {
            return submissionId;
        }

        /** The System the VA Submission belongs to. */
        public UUID systemId() {
            return systemId;
        }

        /**
         * The entry a later refusal of the same review is recorded as: the caller's {@code refusedAs}, now about the System
         * and the VA Submission, before its reason.
         */
        public AuditEvent refused() {
            return refused;
        }
    }

    /**
     * How a step command holds the VA Submission's row: shared for a review, so that reviews run side by side,
     * exclusively for a move to another state, so that it waits for every review in progress and none starts until it
     * commits (ADR 0035 item 3).
     */
    private enum Hold {
        SHARED,
        EXCLUSIVE
    }

    /**
     * What a step command checks beyond ownership and segregation of duties: the step it acts at, the one state it acts
     * from, and the fixed reason and sentence of a refusal in any other state (ADR 0035 item 2, ADR 0042 item 1). The
     * access guard's need is the step's own.
     */
    private record Gate(SubmissionStep step, SubmissionState from, String refusedReason, String refusedSentence) {}

    /** Reviewing, returning and passing to endorsement: the Cyber Manager, In Review. */
    private static final Gate REVIEWING = new Gate(SubmissionStep.REVIEW, SubmissionState.IN_REVIEW,
            "state-not-reviewable",
            "This VA Submission can be reviewed, returned or passed to endorsement only while it is In Review.");

    /** Endorsing: a VA POC, Awaiting Endorsement. */
    private static final Gate ENDORSING = new Gate(SubmissionStep.ENDORSE, SubmissionState.AWAITING_ENDORSEMENT,
            "state-not-endorsable", "This VA Submission can be endorsed only while it is Awaiting Endorsement.");

    /**
     * A VA Submission that passed a {@link Gate}: its row held as {@code hold} says until the caller's transaction ends,
     * and the caller recorded at the gate's step. Only {@link #atStep} makes one, and only one held exclusively is moved.
     *
     * @param refused the entry a later refusal of the same command is recorded as, before its reason
     */
    private record AtStep(UUID submissionId, UUID systemId, Gate gate, AuditEvent refused, Hold hold) {}

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
     * @param endorsedLate whether it was endorsed after the Quarter's last day
     * @param reached when it reached each milestone
     */
    public record Summary(
            UUID id, SubmissionState state, LocalDate submitDue, LocalDate reviewDue, LocalDate endorseDue,
            boolean overdue, @Nullable LocalDate dueOn, boolean endorsedLate, Milestones reached) {}

    /**
     * When a VA Submission last reached each of its milestones (UTC), each null until it has (ADR 0037): submitted for
     * review, passed to endorsement by the Cyber Manager (which ends the review) and endorsed by a VA POC.
     */
    public record Milestones(
            @Nullable Instant submitted, @Nullable Instant passedToEndorsement, @Nullable Instant endorsed) {

        /** A System with no VA Submission has reached none. */
        public static final Milestones NONE = new Milestones(null, null, null);
    }

    /**
     * What the SMO/Tech Lead says about the Declaration when submitting.
     *
     * @param declarationAccepted whether they ticked it; null is not ticked
     * @param declarationVersion the version they were shown; it must be the current one, so what is stored is what they read
     */
    public record DeclarationAnswer(@Nullable Boolean declarationAccepted, @Nullable Integer declarationVersion) {}

    /**
     * What the Cyber Manager says when returning a VA Submission for rework (story 55).
     *
     * @param comment why, for the SMO/Tech Lead beside the Disputed items; optional, as each Dispute carries its own
     */
    public record ReturnRequest(@Nullable String comment) {}

    /**
     * A return for rework as kept: who returned it, at which step and when, what they wrote, and what was Disputed then,
     * each item named by the review that Disputed it (story 55).
     *
     * @param comment cleaned; null when none was given
     * @param returnedBy who (an id, never a name or email)
     * @param returnedAt when (UTC)
     * @param disputedFindings the Disputed Findings, in the order they were read
     * @param disputedReports the Disputed not-applicable justifications, in Scan Report order
     */
    public record Return(
            UUID id, SubmissionStep step, @Nullable String comment, UUID returnedBy, Instant returnedAt,
            List<ReviewProgress.DisputedFinding> disputedFindings, List<ReviewProgress.DisputedReport> disputedReports) {}

    /** The fixed code of the endorse step-up (IM8 ac-2): the intent a fresh sign-in is started for. */
    static final String ENDORSE_INTENT = "submission-endorse";

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
    private final SegregationOfDuties sod;
    private final ReviewProgress progress;
    private final StepUp stepUp;

    Submissions(SubmissionStore store, RegisterSystems register, CycleCalendar calendar, AccessGuard guard,
            CurrentUser user, AuditActor actor, AuditLog audit, Clock clock, SubmitReadiness readiness,
            ApplicationEventPublisher events, SegregationOfDuties sod, ReviewProgress progress, StepUp stepUp) {
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
        this.sod = sod;
        this.progress = progress;
        this.stepUp = stepUp;
    }

    /**
     * Starts the VA Submission for a System the caller is SMO/Tech Lead of.
     *
     * @param cycleYear the VA Cycle's year; null for the one running today (before the first live Cycle starts, that
     *     Cycle)
     * @throws ResourceNotFoundException for a System the caller may not submit for, or a Cycle the app does not run
     * @throws RejectedRequestException 403 {@code sod-conflict} when the caller is also the System's Cyber Manager (story
     *     58: assigned to the reviewing step, so never its submitter); 409 {@code invalid-transition} when the System
     *     already has a VA Submission in the Cycle, or its Quarter is Handled outside the app
     */
    @Transactional
    public Submission start(UUID systemId, @Nullable Integer cycleYear) {
        RegisterSystems.ActiveSystem system = register.active(systemId, Need.SUBMIT);
        sod.require(SubmissionStep.SUBMIT, systemId, null,
                actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_STARTED, AuditOutcome.REFUSED)).onSystem(systemId));
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
     * every write to what the SMO/Tech Lead prepares, segregation of duties (story 58: the caller may not have acted at,
     * or be assigned to, the reviewing step), its state (Draft or Returned for Rework), then everything that
     * stands in the way ({@link SubmitReadiness}) and the Declaration, all listed in one refusal. Holding the row first
     * means no proposal, upload, scan-date confirmation or mode change can land between the checks and the move: each takes
     * the row shared ({@link #lockForEdit}). On success the wording of the Declaration version accepted is kept with who
     * accepted it and when, the VA Submission moves to In Review, the caller is recorded at the submitting step, the submit
     * is audited and a {@link SubmissionEvent} is published.
     *
     * @throws ResourceNotFoundException for a VA Submission that does not exist or of a System the caller is not SMO/Tech
     *     Lead of
     * @throws RejectedRequestException 403 {@code sod-conflict} as above; 409 {@code invalid-transition} when it is not in
     *     Draft or Returned for Rework; 409 {@code not-ready} listing every {@link SubmitReadiness.Blocker} as
     *     {@code reasons}, the Declaration's included
     */
    @Transactional
    public Submission submit(UUID id, DeclarationAnswer answer) {
        UUID systemId = owned(id).systemId();
        SubmissionStore.Located held = store.lockForMove(id).orElseThrow(ResourceNotFoundException::new);
        sod.require(SubmissionStep.SUBMIT, systemId, id, refusedSubmit(id, systemId));
        requireEditable(held, id, systemId, this::notSubmittable);
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
        publish(id, systemId, from, SubmissionState.IN_REVIEW, now);
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
        UUID systemId = owned(id).systemId();
        SubmissionStore.Located held = store.lockForEdit(id).orElseThrow(ResourceNotFoundException::new);
        requireEditable(held, id, systemId, onRefused);
        return new EditTarget(id, systemId);
    }

    /**
     * The only way to write a review of what a VA Submission holds (a Finding's proposal, a not-applicable justification;
     * story 50). Ownership comes first (a VA Submission of a System the caller is not the Cyber Manager of is "not found",
     * ADR 0013); then, with its row held shared until the caller's transaction ends, segregation of duties (story 58: the
     * caller may not have acted at another step on it, nor be assigned to another step of its System; 403
     * {@code sod-conflict}), then the state (In Review; 409 {@code invalid-transition}). Each refusal is audited in an
     * entry of its own (ADR 0014), as {@code refusedAs} about the System and the VA Submission with a fixed reason. The
     * caller is then recorded at the reviewing step, kept only if the review commits.
     *
     * @param refusedAs the entry a refusal is recorded as: the review's action, REFUSED, attributed to the caller
     * @throws ResourceNotFoundException as above
     * @throws RejectedRequestException 403 {@code sod-conflict} or 409 {@code invalid-transition}, as above
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReviewTarget lockForReview(UUID id, AuditEvent refusedAs) {
        AtStep target = atStep(id, REVIEWING, refusedAs, Hold.SHARED);
        return new ReviewTarget(target.submissionId(), target.systemId(), target.refused());
    }

    /**
     * Returns a VA Submission In Review to its SMO/Tech Lead for rework (story 55), with what is Disputed attached: each
     * Disputed Finding and not-applicable justification, named by the review that Disputed it. What the Cyber Manager
     * confirmed stays confirmed, as the move touches no review. The checks are a review's (ADR 0035 item 2), with the VA
     * Submission's row held exclusively, so a review in progress finishes first and none starts until this commits; then
     * the comment, then that something is Disputed. On success it is Returned for Rework, the return is kept, the caller is
     * recorded at the reviewing step, the return is audited and a {@link SubmissionEvent} is published for the SMO/Tech
     * Lead's notification.
     *
     * @throws ResourceNotFoundException for a VA Submission that does not exist or of a System the caller is not Cyber
     *     Manager of
     * @throws RejectedRequestException 403 {@code sod-conflict}; 409 {@code invalid-transition} when it is not In Review;
     *     400 {@code invalid-field} naming {@code comment}; 409 {@code nothing-disputed} when nothing is Disputed
     */
    @Transactional
    public Submission returnForRework(UUID id, ReturnRequest request) {
        AtStep target = atStep(id, REVIEWING,
                actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_RETURNED, AuditOutcome.REFUSED)), Hold.EXCLUSIVE);
        String comment = returnComment(request.comment());
        if (!progress.outcome(id).returnable()) {
            audit.recordIndependently(target.refused().with(DetailKey.REASON, "nothing-disputed"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "nothing-disputed",
                    "Nothing is Disputed, so there is nothing to return for rework: Dispute what needs rework first.");
        }
        ReviewProgress.Disputed disputed = progress.disputed(id);
        Instant now = clock.instant();
        store.insertReturn(id, SubmissionStep.REVIEW, comment, user.id(), now, disputed);
        return moved(target, SubmissionState.RETURNED_FOR_REWORK, now, today -> {
            store.moved(id, SubmissionState.RETURNED_FOR_REWORK, today);
            return AuditEvent.of(AuditAction.SUBMISSION_RETURNED, AuditOutcome.SUCCESS)
                    .with(DetailKey.COUNT, disputed.count());
        });
    }

    /**
     * Passes a VA Submission In Review to the VA POCs for endorsement (story 57), once every Finding and every
     * not-applicable justification is confirmed. The checks are a review's (ADR 0035 item 2), with the VA Submission's row
     * held exclusively, then that nothing is left unconfirmed ({@link ReviewProgress#outcome}), all listed in one
     * refusal. On success it is Awaiting Endorsement, the caller is recorded at the reviewing step, the move is audited and
     * a {@link SubmissionEvent} is published for the VA POCs' notification.
     *
     * @throws ResourceNotFoundException as for a return
     * @throws RejectedRequestException 403 {@code sod-conflict}; 409 {@code invalid-transition} when it is not In Review;
     *     409 {@code not-confirmed} listing what is still unconfirmed as {@code reasons}
     */
    @Transactional
    public Submission passToEndorsement(UUID id) {
        AtStep target = atStep(id, REVIEWING,
                actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_PASSED, AuditOutcome.REFUSED)), Hold.EXCLUSIVE);
        List<SubmitReadiness.Blocker> unconfirmed = progress.outcome(id).endorsementBlockers();
        if (!unconfirmed.isEmpty()) {
            audit.recordIndependently(target.refused().with(DetailKey.REASON, "not-confirmed")
                    .with(DetailKey.COUNT, unconfirmed.size()));
            throw RejectedRequestException.withReasons(HttpStatus.CONFLICT, "not-confirmed",
                    "This VA Submission can pass to endorsement only once every Finding and justification is confirmed. "
                    + "Each reason says what is left.", unconfirmed);
        }
        Instant now = clock.instant();
        return moved(target, SubmissionState.AWAITING_ENDORSEMENT, now, today -> {
            store.passedToEndorsement(id, now, today);
            return AuditEvent.of(AuditAction.SUBMISSION_PASSED, AuditOutcome.SUCCESS);
        });
    }

    /**
     * Endorses a VA Submission Awaiting Endorsement (stories 65, 67 and 70), as a VA POC. It is a step-up action (IM8
     * ac-2), so it is refused first unless the caller signed in again within the freshness window (403
     * {@code step-up-required}). Then ownership (any VA POC may endorse any System's; one that does not exist is "not
     * found"); then, with the VA Submission's row held exclusively, segregation of duties (story 70: a VA POC who acted
     * at another step of it, or whom the Register names as its System's SMO/Tech Lead or Cyber Manager, leaves it to the
     * other VA POC; 403 {@code sod-conflict}) and the state (409 {@code invalid-transition}). On success it is Endorsed,
     * marked Endorsed Late when the Quarter's last day has passed (story 67), the caller is recorded at the endorsing
     * step, the endorsement is audited and a {@link SubmissionEvent} is published for the FYI notification.
     *
     * @throws ResourceNotFoundException for a VA Submission that does not exist
     * @throws RejectedRequestException 403 {@code sod-conflict}; 409 {@code invalid-transition} when it is not Awaiting
     *     Endorsement
     */
    @Transactional
    public Submission endorse(UUID id) {
        stepUp.require(ENDORSE_INTENT);
        AtStep target = atStep(id, ENDORSING,
                actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_ENDORSED, AuditOutcome.REFUSED)), Hold.EXCLUSIVE);
        Instant now = clock.instant();
        return moved(target, SubmissionState.ENDORSED, now, today -> AuditEvent.of(AuditAction.SUBMISSION_ENDORSED,
                AuditOutcome.SUCCESS).with(DetailKey.ENDORSED_LATE, store.endorsed(id, now, today)));
    }

    /**
     * The returns of a VA Submission, the latest first, for anyone who may see its System (one the caller may not see is
     * "not found", ADR 0013). A return carries what a person wrote, so every read is audited as
     * {@code SUBMISSION_RETURNS_VIEWED}, the owner's included, with ids only.
     */
    @Transactional
    public List<Return> returns(UUID id) {
        SubmissionStore.Located located = store.locate(id).orElseThrow(ResourceNotFoundException::new);
        guard.requireSystem(located.systemId(), Need.VIEW);
        List<Return> returns = store.returns(id);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_RETURNS_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(located.systemId()).onSubmission(id));
        return returns;
    }

    /**
     * A step command's checks, in order (ADR 0035 item 2, ADR 0042 item 1): ownership (the step's need over the System;
     * any other is "not found", ADR 0013); then, with the VA Submission's row held as {@code hold} says until the caller's
     * transaction ends, segregation of duties (403 {@code sod-conflict}) and the gate's state (409
     * {@code invalid-transition}). Each refusal is audited in an entry of its own, as {@code refusedAs} about the System
     * and the VA Submission with a fixed reason. The caller is then recorded at the gate's step, kept only if their command
     * commits.
     */
    private AtStep atStep(UUID id, Gate gate, AuditEvent refusedAs, Hold hold) {
        SubmissionStore.Located located = store.locate(id).orElseThrow(ResourceNotFoundException::new);
        guard.requireSystem(located.systemId(), gate.step().need());
        SubmissionStore.Located held = (hold == Hold.EXCLUSIVE ? store.lockForMove(id) : store.lockForEdit(id))
                .orElseThrow(ResourceNotFoundException::new);
        AuditEvent refused = refusedAs.onSystem(held.systemId()).onSubmission(id);
        sod.require(gate.step(), held.systemId(), id, refused);
        if (!gate.from().name().equals(held.state())) {
            audit.recordIndependently(refused.with(DetailKey.REASON, gate.refusedReason()));
            throw new RejectedRequestException(HttpStatus.CONFLICT, NOT_ALLOWED_NOW, gate.refusedSentence());
        }
        store.recordParticipation(id, user.id(), gate.step(), clock.instant());
        return new AtStep(id, held.systemId(), gate, refused, hold);
    }

    /**
     * Moves a VA Submission that passed its gate from the gate's state to {@code to}. {@code write} runs the move's own
     * statement on today's business date ({@link SubmissionStore}: the state, the Overdue flag and the milestone it
     * reaches) and answers the success entry with what the move adds to it; this records that entry with the states and
     * publishes the event the notifications listen for after the commit. Only a VA Submission held exclusively is moved,
     * so a move never overtakes a review in progress (ADR 0039).
     */
    private Submission moved(AtStep target, SubmissionState to, Instant now, Function<LocalDate, AuditEvent> write) {
        if (target.hold() != Hold.EXCLUSIVE) {
            throw new IllegalStateException("a move holds the VA Submission exclusively (ADR 0039)");
        }
        UUID id = target.submissionId();
        SubmissionState from = target.gate().from();
        AuditEvent success = write.apply(calendar.dateOf(now));
        audit.record(actor.attribute(success).onSystem(target.systemId()).onSubmission(id)
                .with(DetailKey.STATE_FROM, from.name()).with(DetailKey.STATE_TO, to.name()));
        publish(id, target.systemId(), from, to, now);
        return view(id);
    }

    /**
     * Publishes the move for the notifications, which listen after the commit (ADR 0043), with the caller as the actor
     * and the last day of the window the new state waits on as the row now reads it (the one rule, V20).
     */
    private void publish(UUID id, UUID systemId, SubmissionState from, SubmissionState to, Instant now) {
        LocalDate dueOn = store.find(id).map(SubmissionStore.Row::dueOn).orElse(null);
        events.publishEvent(new SubmissionEvent(id, systemId, from, to, user.id(), now, dueOn));
    }

    /**
     * The comment of a return, cleaned and bounded as every long text a person types (ADR 0027 item 5, IM8 as-1); null
     * when none was given or nothing is left once cleaned.
     */
    private static @Nullable String returnComment(@Nullable String raw) {
        String cleaned = raw == null ? "" : ReportText.cleanLongText(raw);
        if (cleaned.isEmpty()) {
            return null;
        }
        if (!ReportText.fits(cleaned, ReportText.MAX_LONG_TEXT)) {
            throw RejectedRequestException.invalidField("comment",
                    "The field 'comment' must be at most " + ReportText.MAX_LONG_TEXT + " characters.");
        }
        return cleaned;
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

    /** A VA Submission whose row the caller holds, read in its state: one not in Draft or Returned for Rework is refused. */
    private static void requireEditable(SubmissionStore.Located held, UUID id, UUID systemId, Refusal onRefused) {
        if (!editable(held.state())) {
            throw onRefused.refuse(id, systemId);
        }
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
                            row.overdue(), row.dueOn(), row.endorsedLate(),
                            new Milestones(row.submittedAt(), row.passedToEndorsementAt(), row.endorsedAt())));
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
     * The VA Submissions among these where segregation of duties refuses the caller this step (story 58), for a read model
     * that marks them: the caller acted on it at another step (a Cyber Manager who started or submitted it as its SMO/Tech
     * Lead), or is assigned to another step of its System in the Register. It is the rule the commands refuse by, so the
     * marker and the refusal never disagree. Only the caller's own record and grants are read.
     */
    @Transactional(readOnly = true)
    public Set<UUID> conflictedAt(Collection<UUID> submissionIds, SubmissionStep step) {
        return sod.conflictedAt(submissionIds, step);
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
        audit.recordIndependently(refusedSubmit(id, systemId).with(DetailKey.REASON, "state-not-submittable"));
        return new RejectedRequestException(HttpStatus.CONFLICT, NOT_ALLOWED_NOW,
                "This VA Submission can be submitted only from Draft or Returned for Rework.");
    }

    /**
     * A submit with something in the way: audited in a transaction of its own with how many reasons there are, then 409
     * {@code not-ready} listing them.
     */
    private RejectedRequestException notReady(UUID systemId, UUID id, List<SubmitReadiness.Blocker> blockers) {
        audit.recordIndependently(refusedSubmit(id, systemId).with(DetailKey.REASON, "not-ready")
                .with(DetailKey.COUNT, blockers.size()));
        return RejectedRequestException.withReasons(HttpStatus.CONFLICT, "not-ready",
                "This VA Submission cannot be submitted yet. Each reason says what to do.", blockers);
    }

    /** A refused submit's entry, before its reason: attributed to the caller, about the System and the VA Submission. */
    private AuditEvent refusedSubmit(UUID id, UUID systemId) {
        return actor.attribute(AuditEvent.of(AuditAction.SUBMISSION_SUBMITTED, AuditOutcome.REFUSED))
                .onSystem(systemId).onSubmission(id);
    }
}
