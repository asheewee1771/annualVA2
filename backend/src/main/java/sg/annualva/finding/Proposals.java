package sg.annualva.finding;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.Need;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.submission.Submissions;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The one place the rules and the write of a Finding Status proposal live (stories 35 and 37, ADR 0027 and 0028). A
 * proposal for one Finding ({@link Findings#propose}) and a Bulk-set ({@link BulkProposals#propose}) both run these steps,
 * so each rule, statement and audit entry exists once: a single proposal is a batch of one with no batch row.
 *
 * <ol>
 *   <li>{@link #target}: the VA Submission is one of a System the caller is SMO/Tech Lead of, and in a state that takes
 *       proposals; a refusal for the state is audited in an entry of its own (ADR 0014) and answered 409.
 *   <li>{@link #fields}: the status and its fields ({@link ProposalFields}), and the approval memo.
 *   <li>{@link #lock}: the Findings' rows, in id order, held until the transaction ends.
 *   <li>{@link #write}: one proposal per Finding, each its Finding's current one with the review back to PENDING, and one
 *       {@code STATUS_PROPOSED} entry per Finding (ids and fixed codes only).
 * </ol>
 *
 * Each step must run inside the caller's transaction.
 */
@Component
class Proposals {

    private final FindingStore store;
    private final Submissions submissions;
    private final CycleCalendar calendar;
    private final CurrentUser user;
    private final AuditActor actor;
    private final AuditLog audit;
    private final Clock clock;

    Proposals(FindingStore store, Submissions submissions, CycleCalendar calendar, CurrentUser user, AuditActor actor,
            AuditLog audit, Clock clock) {
        this.store = store;
        this.submissions = submissions;
        this.calendar = calendar;
        this.user = user;
        this.actor = actor;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The VA Submission the proposals go to, once the caller may propose there now.
     *
     * @param refusedAs the action a refusal for the state is recorded as
     * @param findingId the Finding the refusal is about, for a proposal for one Finding; null for a batch
     * @throws ResourceNotFoundException for a VA Submission of a System the caller is not SMO/Tech Lead of
     * @throws RejectedRequestException 409 {@code invalid-transition} when it is not in Draft or Returned for Rework
     */
    Submissions.FileTarget target(UUID submissionId, AuditAction refusedAs, @Nullable UUID findingId) {
        // The files and the Findings are edited in the same states: the SMO/Tech Lead is still preparing the VA Submission.
        Submissions.FileTarget target = submissions.fileTarget(submissionId, Need.SUBMIT);
        if (!target.acceptsFiles()) {
            AuditEvent refused = actor.attribute(AuditEvent.of(refusedAs, AuditOutcome.REFUSED))
                    .onSystem(target.systemId()).onSubmission(submissionId);
            audit.recordIndependently((findingId == null ? refused : refused.onFinding(findingId))
                    .with(DetailKey.REASON, "state-not-editable"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "invalid-transition",
                    "This VA Submission takes no new proposals in its present state.");
        }
        return target;
    }

    /**
     * The status and its fields, read and bounded, with the memo checked against the VA Submission.
     *
     * @throws RejectedRequestException 400 {@code invalid-field} naming the field that is wrong, or the memo when it is not
     *     a supporting evidence file of this VA Submission that has finished its checks
     */
    ProposalFields.Parsed fields(Submissions.FileTarget target, ProposalRequest request) {
        ProposalFields.Parsed fields = ProposalFields.parse(request, calendar.today());
        UUID memo = fields.approvalMemoFileId();
        if (memo != null && !store.isReadyEvidence(memo, target.submissionId())) {
            throw RejectedRequestException.invalidField("approvalMemoFileId",
                    "The field 'approvalMemoFileId' must be a supporting evidence file of this VA Submission that has finished its checks.");
        }
        return fields;
    }

    /** Locks the named Findings of the VA Submission; whether every one named is a Finding of it. */
    boolean lock(Submissions.FileTarget target, List<UUID> findingIds) {
        return store.lockFindings(target.submissionId(), findingIds) == findingIds.size();
    }

    /** Keeps a proposal for each Finding, in the order named, and audits each; the proposals as they were kept. */
    List<ProposalView> write(Submissions.FileTarget target, List<UUID> findingIds, ProposalFields.Parsed fields,
            @Nullable UUID batchId) {
        Instant now = clock.instant();
        List<UUID> proposalIds = store.propose(findingIds, fields, batchId, user.id(), now);
        UUID memo = fields.approvalMemoFileId();
        for (UUID findingId : findingIds) {
            AuditEvent proposed = actor.attribute(AuditEvent.of(AuditAction.STATUS_PROPOSED, AuditOutcome.SUCCESS))
                    .onSystem(target.systemId()).onSubmission(target.submissionId()).onFinding(findingId)
                    .with(DetailKey.FINDING_STATUS, fields.status().name());
            AuditEvent inBatch = batchId == null ? proposed : proposed.with(DetailKey.BATCH_ID, batchId);
            audit.record(memo == null ? inBatch : inBatch.with(DetailKey.FILE_ID, memo));
        }
        return proposalIds.stream()
                .map(id -> new ProposalView(id, fields.status(), fields.evidence(), fields.justification(),
                        fields.reason(), fields.approverName(), fields.approverRole(), memo, fields.plan(),
                        fields.targetDate(), now, batchId))
                .toList();
    }

    /** A new Bulk-set batch of the VA Submission, by the caller, now. */
    UUID newBatch(Submissions.FileTarget target) {
        return store.newBatch(target.submissionId(), user.id(), clock.instant());
    }
}
