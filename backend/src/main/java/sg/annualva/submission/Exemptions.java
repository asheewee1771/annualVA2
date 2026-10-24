package sg.annualva.submission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 * exclusively, and each move published for the notifications. A decision is a step-up action (IM8 ac-2). The evidence a
 * person wrote is kept and shown as data, and never logged or audited.
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
     * @param conditional an approved Exemption resting on a scheduled VA
     * @param lapsesOn for a conditional Exemption, the last day its VA may be recorded by: the Quarter's last day plus 3
     *     months
     */
    public record Exemption(
            UUID id, UUID submissionId, UUID systemId, Basis basis, LocalDate vaDate, String evidence,
            @Nullable UUID evidenceFileId, SubmissionState previousState, UUID requestedBy, Instant requestedAt,
            @Nullable Decision decision, @Nullable UUID decidedBy, @Nullable Instant decidedAt, boolean conditional,
            @Nullable LocalDate lapsesOn) {}

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

    Exemptions(Submissions submissions, SubmissionStore store, ExemptionStore exemptions, SubmitReadiness readiness,
            ExemptionEvidence evidence, CycleCalendar calendar, AccessGuard guard, CurrentUser user, AuditActor actor,
            AuditLog audit, StepUp stepUp, Clock clock) {
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
     * The pending requests of these VA Submissions, oldest first, for the VA POC's queue (story 45); only those of Systems
     * the caller may see. They carry the evidence a person wrote, so every read is audited as {@code EXEMPTIONS_VIEWED}
     * with the count, never the text.
     */
    @Transactional
    public List<Exemption> pending(Collection<UUID> submissionIds) {
        var scope = guard.scope(Need.VIEW);
        List<Exemption> pending = exemptions.pending(submissionIds).stream()
                .filter(row -> scope.contains(row.systemId())).map(Exemptions::view).toList();
        audit.record(actor.attribute(AuditEvent.of(AuditAction.EXEMPTIONS_VIEWED, AuditOutcome.SUCCESS))
                .with(DetailKey.COUNT, pending.size()));
        return pending;
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
        Submissions.AtStep target = submissions.atStep(id, DECIDING, actor.attribute(AuditEvent.of(action,
                AuditOutcome.REFUSED)).with(DetailKey.EXEMPTION_ID, exemptionId), Submissions.Hold.EXCLUSIVE);
        LocalDate lapsesOn = conditional(decision, request.basis())
                ? ExemptionFields.lapsesOn(quarterOf(store.find(id).orElseThrow(ResourceNotFoundException::new)))
                : null;
        Instant now = clock.instant();
        if (!exemptions.decide(exemptionId, decision, user.id(), now, lapsesOn)) {
            audit.recordIndependently(target.refused().with(DetailKey.REASON, "already-decided"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, NOT_ALLOWED_NOW,
                    "This Exemption request has been decided already.");
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
                row.decidedBy(), row.decidedAt(), conditional(row.decision(), row.basis()), row.lapsesOn());
    }
}
