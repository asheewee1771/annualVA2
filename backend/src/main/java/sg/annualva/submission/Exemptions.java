package sg.annualva.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * Exemptions (stories 43 to 46, ADR 0048). An SMO/Tech Lead requests one for their System at any time before it is
 * endorsed, and the VA Submission is frozen in Exemption Requested; a VA POC approves it (Exempted, final, the work kept
 * for audit; conditional when it rests on a scheduled VA) or rejects it (back to exactly the state it was in, with the
 * Overdue flag it had). Both are step commands through {@link Submissions#atStep}: the request at the submitting step,
 * the decision at the VA POC's endorsing step, each with segregation of duties, the closed-Cycle refusal and the row held
 * exclusively, and each move published for the notifications. A decision is a step-up action (IM8 ac-2). An approval on
 * a scheduled VA is a conditional Exemption (story 47, ADR 0050): its SMO/Tech Lead records that the VA happened, at the
 * submitting step, and a VA POC confirms it, at the endorsing step after a fresh sign-in; both are allowed after the VA
 * Cycle has closed, and one with nothing recorded by its lapse date lapses ({@link ExemptionLapse}). The evidence a person
 * wrote is kept and shown as data, and never logged or audited.
 */
@Service
public class Exemptions {

    /** What an Exemption rests on. */
    public enum Basis {
        /** A VA done within the 3 months before the Quarter. */
        PRIOR_VA,
        /** A VA scheduled within the 3 months after the Quarter: a conditional Exemption once approved (ticket 42). */
        SCHEDULED_VA
    }

    /** What a VA POC decided, with the entry it is audited as. */
    public enum Decision {
        APPROVED(AuditAction.EXEMPTION_APPROVED),
        REJECTED(AuditAction.EXEMPTION_REJECTED);

        private final AuditAction action;

        Decision(AuditAction action) {
            this.action = action;
        }

        /** The state the VA Submission moves to: Exempted, or back to the state the request froze. */
        SubmissionState to(SubmissionState frozen) {
            return this == APPROVED ? SubmissionState.EXEMPTED : frozen;
        }
    }

    /** An approved Exemption resting on a scheduled VA is conditional until its VA is confirmed (ticket 42). */
    static boolean conditional(@Nullable Decision decision, Basis basis) {
        return decision == Decision.APPROVED && basis == Basis.SCHEDULED_VA;
    }

    /**
     * What the SMO/Tech Lead's request form sends; every field is checked by {@link ExemptionFields}.
     *
     * @param basis {@code PRIOR_VA} or {@code SCHEDULED_VA}
     * @param vaDate the VA's date, yyyy-mm-dd
     * @param evidence what shows the VA happened or is booked: long text
     * @param evidenceFileId optionally, a READY supporting evidence file of the same VA Submission
     * @param cycle the VA Cycle's year; null for the one running today
     */
    public record Request(@Nullable String basis, @Nullable String vaDate, @Nullable String evidence,
            @Nullable UUID evidenceFileId, @Nullable Integer cycle) {}

    /**
     * An Exemption request and its decision, as its SMO/Tech Lead and the VA POCs read it.
     *
     * @param previousState the state a rejection restores ({@code NOT_STARTED} when the request created the VA Submission)
     * @param requestedBy who requested it (an id, never a name or email)
     * @param decision null while pending
     * @param conditional an approved Exemption resting on a scheduled VA whose VA no VA POC has confirmed yet and that
     *     has not lapsed (ticket 42)
     * @param lapsesOn for an approved Exemption resting on a scheduled VA, the last day its VA may be recorded by: the
     *     Quarter's last day plus 3 months
     * @param vaHappenedOn the day its SMO/Tech Lead says the scheduled VA happened; null until they record it
     * @param vaEvidence what they wrote about it: data, shown only as text
     * @param vaRecordedBy who recorded it (an id); from then on the Exemption does not lapse
     * @param confirmedBy the VA POC who confirmed the recorded VA (an id); the Exemption is then unconditional
     * @param lapsedAt when the lapse job lapsed it, nothing having been recorded by {@code lapsesOn}
     */
    public record Exemption(
            UUID id, UUID submissionId, UUID systemId, Basis basis, LocalDate vaDate, String evidence,
            @Nullable UUID evidenceFileId, SubmissionState previousState, UUID requestedBy, Instant requestedAt,
            @Nullable Decision decision, @Nullable UUID decidedBy, @Nullable Instant decidedAt, boolean conditional,
            @Nullable LocalDate lapsesOn, @Nullable LocalDate vaHappenedOn, @Nullable String vaEvidence,
            @Nullable UUID vaRecordedBy, @Nullable Instant vaRecordedAt, @Nullable UUID confirmedBy,
            @Nullable Instant confirmedAt, @Nullable Instant lapsedAt) {}

    /**
     * What the SMO/Tech Lead's VA happened form sends (ticket 42); every field is checked by {@link ExemptionFields}.
     *
     * @param vaDate the day the scheduled VA happened, yyyy-mm-dd
     * @param evidence what shows it happened: long text
     */
    public record VaHappened(@Nullable String vaDate, @Nullable String evidence) {}

    /** The fixed code of the decision step-up (IM8 ac-2): approving and rejecting alike. */
    static final String DECIDE_INTENT = "exemption-decide";

    /** Requesting: the SMO/Tech Lead, from any state before Endorsed in which nothing is pending. */
    private static final Submissions.Gate REQUESTING = new Submissions.Gate(SubmissionStep.SUBMIT,
            EnumSet.of(SubmissionState.NOT_STARTED, SubmissionState.DRAFT, SubmissionState.IN_REVIEW,
                    SubmissionState.RETURNED_FOR_REWORK, SubmissionState.AWAITING_ENDORSEMENT),
            "state-not-exemptible",
            "An Exemption can be requested only before the VA Submission is endorsed, and not while one is pending.");

    /** Deciding: a VA POC, at the endorsing step (ADR 0042), while the VA Submission is Exemption Requested. */
    private static final Submissions.Gate DECIDING = new Submissions.Gate(SubmissionStep.ENDORSE,
            EnumSet.of(SubmissionState.EXEMPTION_REQUESTED), "state-not-decidable",
            "This Exemption can be decided only while its VA Submission is Exemption Requested.");

    /**
     * Recording that a conditional Exemption's scheduled VA happened: the SMO/Tech Lead, while it is Exempted. Allowed
     * after the VA Cycle has closed (spec: a Q4 conditional Exemption resolves after the close; ADR 0044 item 6).
     */
    private static final Submissions.Gate RECORDING = Submissions.Gate.afterClose(SubmissionStep.SUBMIT,
            EnumSet.of(SubmissionState.EXEMPTED), "state-not-exempted",
            "The VA can be recorded only while the VA Submission is Exempted on a conditional Exemption.");

    /** Confirming a recorded VA: a VA POC, at the endorsing step, while it is Exempted; after the close too. */
    private static final Submissions.Gate CONFIRMING = Submissions.Gate.afterClose(SubmissionStep.ENDORSE,
            EnumSet.of(SubmissionState.EXEMPTED), "state-not-exempted",
            "The VA can be confirmed only while the VA Submission is Exempted on a conditional Exemption.");

    private static final String NOT_ALLOWED_NOW = "invalid-transition";

    private final Submissions submissions;
    private final SubmissionStore store;
    private final ExemptionStore exemptions;
    private final SubmitReadiness readiness;
    private final ExemptionEvidence evidence;
    private final CycleCalendar calendar;
    private final AccessGuard guard;
    private final CurrentUser user;
    private final AuditActor actor;
    private final AuditLog audit;
    private final StepUp stepUp;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    Exemptions(Submissions submissions, SubmissionStore store, ExemptionStore exemptions, SubmitReadiness readiness,
            ExemptionEvidence evidence, CycleCalendar calendar, AccessGuard guard, CurrentUser user, AuditActor actor,
            AuditLog audit, StepUp stepUp, Clock clock, ApplicationEventPublisher events) {
        this.events = events;
        this.submissions = submissions;
        this.store = store;
        this.exemptions = exemptions;
        this.readiness = readiness;
        this.evidence = evidence;
        this.calendar = calendar;
        this.guard = guard;
        this.user = user;
        this.actor = actor;
        this.audit = audit;
        this.stepUp = stepUp;
        this.clock = clock;
    }

    /**
     * Requests an Exemption for a System the caller is SMO/Tech Lead of (stories 43 and 44). The checks run in the order
     * ownership (another System is "not found"), the Cycle (open, the System in a Quarter the app runs, as for a start),
     * then at the submitting step with the VA Submission's row held exclusively: segregation of duties (403
     * {@code sod-conflict}) and the state (Not Started, Draft, In Review, Returned for Rework or Awaiting Endorsement; 409
     * {@code invalid-transition}); then the fields (400 {@code invalid-field}); then that no file of the VA Submission is
     * still being checked, a rescan included (409 {@code files-scanning} naming them; reviewer decisions 2026-10-18), so a
     * settlement never lands in a frozen VA Submission; then the evidence file. A System with no VA Submission gets one,
     * created Not Started, in the same transaction. On success the request is kept with the state and Overdue flag it
     * froze, the VA Submission is Exemption Requested, the caller is recorded at the submitting step, the request is
     * audited and a {@link SubmissionEvent} is published for both VA POCs' notification.
     */
    @Transactional
    public Exemption request(UUID systemId, Request request) {
        AuditEvent refusedAs = actor.attribute(AuditEvent.of(AuditAction.EXEMPTION_REQUESTED, AuditOutcome.REFUSED));
        UUID id = submissions.forRequest(systemId, request.cycle(), refusedAs);
        Submissions.AtStep target = submissions.atStep(id, REQUESTING, refusedAs, Submissions.Hold.EXCLUSIVE);
        SubmissionStore.Row row = store.find(id).orElseThrow(ResourceNotFoundException::new);
        ExemptionFields.Parsed fields = ExemptionFields.parse(request, quarterOf(row), calendar.today());
        requireNothingScanning(target);
        UUID file = fields.evidenceFileId();
        if (file != null && !evidence.isReadyEvidence(id, file)) {
            throw RejectedRequestException.invalidField("evidenceFileId", "The field 'evidenceFileId' must name a "
                    + "supporting evidence file of this VA Submission that has passed every check.");
        }
        Instant now = clock.instant();
        UUID exemptionId = exemptions.insert(id, fields, target.from(), row.overdueSince(), user.id(), now);
        submissions.moved(target, SubmissionState.EXEMPTION_REQUESTED, now, today -> {
            store.moved(id, SubmissionState.EXEMPTION_REQUESTED, today);
            return AuditEvent.of(AuditAction.EXEMPTION_REQUESTED, AuditOutcome.SUCCESS)
                    .with(DetailKey.EXEMPTION_ID, exemptionId).with(DetailKey.BASIS, fields.basis().name());
        });
        return view(exemptionId);
    }

    /**
     * Approves a pending Exemption (story 46): the VA Submission is Exempted, its work kept as it is; conditional, with
     * the lapse date, when it rests on a scheduled VA. See {@link #decided} for the checks.
     */
    @Transactional
    public Exemption approve(UUID exemptionId) {
        stepUp.require(DECIDE_INTENT);
        return decided(exemptionId, Decision.APPROVED);
    }

    /**
     * Rejects a pending Exemption (stories 44 and 46): the VA Submission goes back to exactly the state the request froze
     * (Not Started when the request created it), with the Overdue flag it had if that state's window has still passed.
     * See {@link #decided} for the checks.
     */
    @Transactional
    public Exemption reject(UUID exemptionId) {
        stepUp.require(DECIDE_INTENT);
        return decided(exemptionId, Decision.REJECTED);
    }

    /**
     * Records that a conditional Exemption's scheduled VA happened (story 47), as the SMO/Tech Lead of its System. The
     * checks, in order: the Exemption (one that does not exist is "not found"); at the submitting step with the VA
     * Submission's row held exclusively: ownership (another System's is "not found"), segregation of duties (403
     * {@code sod-conflict}) and the state (Exempted; 409 {@code invalid-transition}), but not the closed Cycle; then that it
     * is conditional with nothing recorded yet and its lapse date not passed (409 {@code invalid-transition} with a
     * reason); then the fields (400 {@code invalid-field}). From then on it does not lapse; it stays conditional until a
     * VA POC confirms it. Audited as {@code EXEMPTION_VA_RECORDED}, never the evidence, and both VA POCs are asked to
     * confirm it.
     */
    @Transactional
    public Exemption recordVa(UUID exemptionId, VaHappened happened) {
        ExemptionStore.Row exemption = exemptions.find(exemptionId).orElseThrow(ResourceNotFoundException::new);
        Submissions.AtStep target = submissions.atStep(exemption.submissionId(), RECORDING,
                refusedAs(AuditAction.EXEMPTION_VA_RECORDED, exemptionId), Submissions.Hold.EXCLUSIVE);
        LocalDate today = calendar.today();
        LocalDate lapsesOn = exemption.lapsesOn();
        if (lapsesOn == null || exemption.confirmedAt() != null) {
            throw refused(target, "not-conditional", "This Exemption is not conditional: there is no VA to record.");
        }
        if (exemption.vaRecordedAt() != null) {
            throw refused(target, "already-recorded", "This Exemption's VA has been recorded already.");
        }
        if (exemption.lapsedAt() != null || today.isAfter(lapsesOn)) {
            throw refused(target, "exemption-lapsed",
                    "This conditional Exemption lapsed: its VA was not recorded by its lapse date.");
        }
        ExemptionFields.Happened fields = ExemptionFields.happened(happened, quarterOf(target), today);
        Instant now = clock.instant();
        exemptions.recordVa(exemptionId, fields.vaDate(), fields.evidence(), user.id(), now);
        return succeeded(target, AuditAction.EXEMPTION_VA_RECORDED, ExemptionEvent.Kind.VA_RECORDED, exemptionId, now);
    }

    /**
     * Confirms the recorded VA of a conditional Exemption (story 47), as a VA POC, after a fresh sign-in (step-up
     * {@code exemption-decide}, checked first: every Exemption decision is a step-up action). Then the Exemption ("not
     * found" when it does not exist); then at the endorsing step with the VA Submission's row held exclusively: segregation
     * of duties (403 {@code sod-conflict}: a VA POC the Register names as the System's SMO/Tech Lead or Cyber Manager, or
     * who acted at another step of it, leaves it to the other VA POC) and the state (Exempted), but not the closed Cycle;
     * then that a VA is recorded and not confirmed yet (409 {@code invalid-transition} with a reason). The Exemption is
     * then unconditional; audited as {@code EXEMPTION_VA_CONFIRMED}; the other VA POC's item clears and the SMO/Tech Lead
     * is told.
     */
    @Transactional
    public Exemption confirmVa(UUID exemptionId) {
        stepUp.require(DECIDE_INTENT);
        ExemptionStore.Row exemption = exemptions.find(exemptionId).orElseThrow(ResourceNotFoundException::new);
        Submissions.AtStep target = submissions.atStep(exemption.submissionId(), CONFIRMING,
                refusedAs(AuditAction.EXEMPTION_VA_CONFIRMED, exemptionId), Submissions.Hold.EXCLUSIVE);
        if (exemption.vaRecordedAt() == null) {
            throw refused(target, "va-not-recorded", "Nothing to confirm: the SMO/Tech Lead has not recorded the VA.");
        }
        Instant now = clock.instant();
        if (!exemptions.confirmVa(exemptionId, user.id(), now)) {
            throw refused(target, "already-confirmed", "This Exemption's VA has been confirmed already.");
        }
        return succeeded(target, AuditAction.EXEMPTION_VA_CONFIRMED, ExemptionEvent.Kind.VA_CONFIRMED, exemptionId, now);
    }

    /** A refused command on an Exemption, before its System, VA Submission and reason: attributed to the caller. */
    private AuditEvent refusedAs(AuditAction action, UUID exemptionId) {
        return actor.attribute(AuditEvent.of(action, AuditOutcome.REFUSED)).with(DetailKey.EXEMPTION_ID, exemptionId);
    }

    /** Audits a refusal in an entry of its own (ADR 0014) with its fixed reason, then answers 409. */
    private RejectedRequestException refused(Submissions.AtStep target, String reason, String sentence) {
        audit.recordIndependently(target.refused().with(DetailKey.REASON, reason));
        return new RejectedRequestException(HttpStatus.CONFLICT, NOT_ALLOWED_NOW, sentence);
    }

    /**
     * Audits a command on an Exemption that moves no state, about its System and VA Submission, and publishes it for the
     * notifications, which listen after the commit.
     */
    private Exemption succeeded(Submissions.AtStep target, AuditAction action, ExemptionEvent.Kind kind,
            UUID exemptionId, Instant now) {
        audit.record(actor.attribute(AuditEvent.of(action, AuditOutcome.SUCCESS)).onSystem(target.systemId())
                .onSubmission(target.submissionId()).with(DetailKey.EXEMPTION_ID, exemptionId));
        events.publishEvent(new ExemptionEvent(kind, exemptionId, target.submissionId(), target.systemId(),
                target.from(), null, user.id(), now));
        return view(exemptionId);
    }

    /**
     * What waits on a VA POC in their queue.
     *
     * @param pending the pending requests, oldest first (story 45)
     * @param toConfirm the recorded VAs of conditional Exemptions waiting on a confirmation, the earliest recorded first
     *     (story 47)
     */
    public record Waiting(List<Exemption> pending, List<Exemption> toConfirm) {}

    /**
     * What waits on a VA POC: the pending requests of these frozen VA Submissions and the recorded VAs to confirm of these
     * Exempted ones; only those of Systems the caller may see. They carry what a person wrote, so every read is audited as
     * {@code EXEMPTIONS_VIEWED} with the count of both, never the text.
     */
    @Transactional
    public Waiting waiting(Collection<UUID> frozen, Collection<UUID> exempted) {
        var scope = guard.scope(Need.VIEW);
        List<Exemption> pending = exemptions.pending(frozen).stream()
                .filter(row -> scope.contains(row.systemId())).map(Exemptions::view).toList();
        List<Exemption> toConfirm = exemptions.toConfirm(exempted).stream()
                .filter(row -> scope.contains(row.systemId())).map(Exemptions::view).toList();
        audit.record(actor.attribute(AuditEvent.of(AuditAction.EXEMPTIONS_VIEWED, AuditOutcome.SUCCESS))
                .with(DetailKey.COUNT, pending.size() + toConfirm.size()));
        return new Waiting(pending, toConfirm);
    }

    /**
     * A decision's checks, in order: step-up first (403 {@code step-up-required}, before anything is read, as for an
     * endorsement), then the request (one that does not exist is "not found"), then at the endorsing step with the VA
     * Submission's row held exclusively: segregation of duties (a VA POC who is the System's SMO/Tech Lead or Cyber
     * Manager, or acted at another step of it, leaves it to the other VA POC; 403 {@code sod-conflict}), the state
     * (Exemption Requested; 409 {@code invalid-transition}) and that this request is the pending one (409
     * {@code invalid-transition}). On success the decision is kept, the VA Submission moves, the caller is recorded at the
     * endorsing step, the decision is audited and a {@link SubmissionEvent} is published, on which the SMO/Tech Lead is
     * told and the other VA POC's item clears.
     */
    private Exemption decided(UUID exemptionId, Decision decision) {
        AuditAction action = decision.action;
        ExemptionStore.Row request = exemptions.find(exemptionId).orElseThrow(ResourceNotFoundException::new);
        UUID id = request.submissionId();
        Submissions.AtStep target = submissions.atStep(id, DECIDING, refusedAs(action, exemptionId),
                Submissions.Hold.EXCLUSIVE);
        LocalDate lapsesOn = conditional(decision, request.basis()) ? ExemptionFields.lapsesOn(quarterOf(target)) : null;
        Instant now = clock.instant();
        if (!exemptions.decide(exemptionId, decision, user.id(), now, lapsesOn)) {
            throw refused(target, "already-decided", "This Exemption request has been decided already.");
        }
        SubmissionState to = decision.to(request.previousState());
        submissions.moved(target, to, now, today -> {
            // The flag's time from before the freeze comes back only if the new state's window has passed: never for
            // Exempted, which waits on no window.
            store.restored(id, to, request.previousOverdueSince(), today);
            return AuditEvent.of(action, AuditOutcome.SUCCESS).with(DetailKey.EXEMPTION_ID, exemptionId);
        });
        return view(exemptionId);
    }

    /**
     * Refuses a request while any file of the VA Submission is still being checked, by the one rule the submit refuses
     * by ({@link SubmitReadiness#FILES_SCANNING}): audited in an entry of its own, then 409 {@code files-scanning} naming
     * the files.
     */
    private void requireNothingScanning(Submissions.AtStep target) {
        List<SubmitReadiness.Blocker> scanning = readiness.blockers(target.submissionId()).stream()
                .filter(blocker -> blocker.reason().equals(SubmitReadiness.FILES_SCANNING)).toList();
        if (!scanning.isEmpty()) {
            audit.recordIndependently(target.refused().with(DetailKey.REASON, SubmitReadiness.FILES_SCANNING));
            throw RejectedRequestException.withReasons(HttpStatus.CONFLICT, SubmitReadiness.FILES_SCANNING,
                    "An Exemption cannot be requested while a file of this VA Submission is still being checked. "
                    + "Wait until every one has finished.", scanning);
        }
    }

    private Quarter quarterOf(Submissions.AtStep target) {
        return quarterOf(store.find(target.submissionId()).orElseThrow(ResourceNotFoundException::new));
    }

    private Quarter quarterOf(SubmissionStore.Row row) {
        return calendar.cycle(row.cycleYear()).orElseThrow(ResourceNotFoundException::new).quarters()
                .get(row.quarter() - 1);
    }

    private Exemption view(UUID exemptionId) {
        return view(exemptions.find(exemptionId).orElseThrow(ResourceNotFoundException::new));
    }

    private static Exemption view(ExemptionStore.Row row) {
        return new Exemption(row.id(), row.submissionId(), row.systemId(), row.basis(), row.vaDate(), row.evidence(),
                row.evidenceFileId(), row.previousState(), row.requestedBy(), row.requestedAt(), row.decision(),
                row.decidedBy(), row.decidedAt(), row.conditional(),
                row.lapsesOn(), row.vaHappenedOn(), row.vaEvidence(), row.vaRecordedBy(), row.vaRecordedAt(),
                row.confirmedBy(), row.confirmedAt(), row.lapsedAt());
    }
}
