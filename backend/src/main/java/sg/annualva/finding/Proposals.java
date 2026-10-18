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
        return editable(submissionId, refusedAs, findingId, "This VA Submission takes no new proposals in its present state.");
    }

    /**
     * The VA Submission, once the caller may change what the SMO/Tech Lead prepares in it now: its Findings, their proposals
     * and how its Scan Reports are provided (ticket 22). The same rule as {@link #target}, with the refusal's own sentence.
     *
     * @param refusal the fixed sentence of the 409
     */
    Submissions.FileTarget editable(UUID submissionId, AuditAction refusedAs, @Nullable UUID findingId, String refusal) {
        // The files and the Findings are edited in the same states: the SMO/Tech Lead is still preparing the VA Submission.
        Submissions.FileTarget target = submissions.fileTarget(submissionId, Need.SUBMIT);
        if (!target.acceptsFiles()) {
            AuditEvent refused = actor.attribute(AuditEvent.of(refusedAs, AuditOutcome.REFUSED))
                    .onSystem(target.systemId()).onSubmission(submissionId);
            audit.recordIndependently((findingId == null ? refused : refused.onFinding(findingId))
                    .with(DetailKey.REASON, "state-not-editable"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "invalid-transition", refusal);
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

    /**
     * Who proposals are made by. The caller ({@link #caller}) is the SMO/Tech Lead of the request; a rescan
     * ({@link #rescan}) is the scan job acting for the SMO/Tech Lead who uploaded it, recorded as made by them and named as
     * the actor of the audit entry, with {@code proposed_as} RESCAN, the rescan's file and the file it replaced.
     *
     * @param rescanFileId the rescan whose settlement made the proposals; null for the caller's
     * @param replacedFile the file the rescan replaced; null for the caller's
     */
    record Proposer(
            UUID id, ProposedAs as, @Nullable UUID rescanFileId, ProposalView.@Nullable ReplacedFile replacedFile) {

        static Proposer rescan(UUID uploadedBy, UUID rescanFileId, ProposalView.ReplacedFile replacedFile) {
            return new Proposer(uploadedBy, ProposedAs.RESCAN, rescanFileId, replacedFile);
        }
    }

    /** The SMO/Tech Lead of the current request, as the one who proposes. */
    Proposer caller() {
        return new Proposer(user.id(), ProposedAs.SMO, null, null);
    }

    /**
     * Keeps a proposal for each Finding, in the order named, and audits each: the one place every proposal is written and
     * every {@code STATUS_PROPOSED} entry is built, for the caller and for a rescan alike. An entry always carries
     * {@code finding_status} and {@code proposed_as}, then {@code batch_id} for a Bulk-set, {@code file_id} for an
     * approval memo, and {@code rescan_file_id} and {@code replaced_file_id} for a rescan: each key means one thing.
     *
     * @return the proposals as they were kept
     */
    List<ProposalView> write(UUID submissionId, UUID systemId, List<UUID> findingIds, ProposalFields.Parsed fields,
            @Nullable UUID batchId, Proposer by) {
        Instant now = clock.instant();
        ProposalView.ReplacedFile replaced = by.replacedFile();
        List<UUID> proposalIds = store.propose(findingIds, fields, batchId, by.as(),
                replaced == null ? null : replaced.id(), by.id(), now);
        UUID memo = fields.approvalMemoFileId();
        for (UUID findingId : findingIds) {
            AuditEvent proposed = attributed(AuditEvent.of(AuditAction.STATUS_PROPOSED, AuditOutcome.SUCCESS), by)
                    .onSystem(systemId).onSubmission(submissionId).onFinding(findingId)
                    .with(DetailKey.FINDING_STATUS, fields.status().name())
                    .with(DetailKey.PROPOSED_AS, by.as().name());
            proposed = withId(proposed, DetailKey.BATCH_ID, batchId);
            proposed = withId(proposed, DetailKey.FILE_ID, memo);
            proposed = withId(proposed, DetailKey.RESCAN_FILE_ID, by.rescanFileId());
            audit.record(withId(proposed, DetailKey.REPLACED_FILE_ID, replaced == null ? null : replaced.id()));
        }
        return proposalIds.stream()
                .map(id -> new ProposalView(id, fields.status(), fields.evidence(), fields.justification(),
                        fields.reason(), fields.approverName(), fields.approverRole(), memo, fields.plan(),
                        fields.targetDate(), now, batchId, by.as(), replaced))
                .toList();
    }

    private AuditEvent attributed(AuditEvent event, Proposer by) {
        return by.as() == ProposedAs.SMO ? actor.attribute(event) : actor.onBehalfOf(event, by.id());
    }

    private static AuditEvent withId(AuditEvent event, DetailKey key, @Nullable UUID id) {
        return id == null ? event : event.with(key, id);
    }

    /** A new Bulk-set batch of the VA Submission, by the caller, now. */
    UUID newBatch(Submissions.FileTarget target) {
        return store.newBatch(target.submissionId(), user.id(), clock.instant());
    }
}
