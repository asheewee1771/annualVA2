package sg.annualva.finding;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.submission.Submissions;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The Cyber Manager's review (story 50): they confirm each proposed Finding Status of a VA Submission In Review, or mark it
 * Disputed with a comment, and do the same for the justification of a Scan Report marked not applicable (ADR 0030 item 3)
 * and, at once, for the members of a Bulk-set batch (story 51). Each command checks, in this order: ownership (the Cyber
 * Manager assigned to the System; any other is "not found", ADR 0013), segregation of duties (story 58), the state (In
 * Review), the fields, then, with the Finding, the members or the Scan Report held, that what was reviewed is still what is
 * there. Every refusal but a 400 or a 404 is audited in an entry of its own (ADR 0014). A review is a new row, never a
 * change to an earlier one; the comment and the justification are data and never reach a log line, an audit entry or an
 * error body.
 */
@Service
public class Reviews {

    private final ReviewStore store;
    private final FindingStore findings;
    private final Submissions submissions;
    private final CurrentUser user;
    private final AuditActor actor;
    private final AuditLog audit;
    private final Clock clock;

    Reviews(ReviewStore store, FindingStore findings, Submissions submissions, CurrentUser user, AuditActor actor,
            AuditLog audit, Clock clock) {
        this.store = store;
        this.findings = findings;
        this.submissions = submissions;
        this.user = user;
        this.actor = actor;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Confirms or Disputes the current proposal of a Finding. The request names the proposal the Cyber Manager read, and
     * it must still be the Finding's current one, so a screen read before the SMO/Tech Lead proposed again never confirms
     * what the Cyber Manager has not seen. The Finding is CONFIRMED or DISPUTED only while that proposal stays current: a
     * new proposal puts it back to PENDING (ADR 0027 item 2). The caller is recorded at the reviewing step.
     *
     * @throws ResourceNotFoundException for a Finding that does not exist or of a System the caller is not Cyber Manager of
     * @throws RejectedRequestException 403 {@code sod-conflict}; 409 {@code invalid-transition} when the VA Submission is
     *     not In Review; 400 {@code invalid-field} naming {@code decision}, {@code comment} or {@code proposalId}; 409
     *     {@code proposal-changed} when the proposal named is not the Finding's current one
     */
    @Transactional
    public ReviewView reviewFinding(UUID findingId, ReviewRequest request) {
        FindingStore.Located located = findings.locate(findingId).orElseThrow(ResourceNotFoundException::new);
        AuditEvent refused = actor.attribute(AuditEvent.of(AuditAction.FINDING_REVIEWED, AuditOutcome.REFUSED))
                .onFinding(findingId);
        Submissions.ReviewTarget target = submissions.lockForReview(located.submissionId(), refused);
        ReviewFields.Parsed fields = ReviewFields.ofProposal(request);
        ReviewStore.LockedFinding finding = store.lockFinding(target, findingId)
                // Located a moment ago in this VA Submission; Findings are never moved.
                .orElseThrow(ResourceNotFoundException::new);
        if (!Objects.equals(finding.currentProposal(), fields.proposalId())) {
            audit.recordIndependently(target.refused().with(DetailKey.REASON, "proposal-changed"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "proposal-changed",
                    "The proposal reviewed is no longer this Finding's current one: read it again before reviewing.");
        }
        List<ReviewStore.Reviewed> one =
                List.of(new ReviewStore.Reviewed(findingId, Objects.requireNonNull(fields.proposalId())));
        ReviewView review = store.reviewFindings(target, one, fields, null, user.id(), clock.instant()).getFirst();
        recordFindingReviewed(target, findingId, fields.decision(), null);
        return review;
    }

    /**
     * Confirms or Disputes, with one comment, every member of a Bulk-set batch whose current proposal is the batch's and
     * awaits review (story 51). A batch's proposals never change, so a member whose current proposal is still the batch's
     * is exactly what the screen showed. A member changed since is left out and named in the answer: proposed again (or
     * withdrawn by a rescan), or already confirmed or Disputed, by a review of its own or an earlier one of the batch. The
     * members are held in id order. It is one {@code BATCH_REVIEWED} entry, and a {@code FINDING_REVIEWED} entry per
     * Finding decided with the batch id, so the audit trail of a Finding shows it, as a Bulk-set does (ADR 0028 item 5).
     * The caller is recorded at the reviewing step.
     *
     * @throws ResourceNotFoundException for a batch that does not exist or of a System the caller is not Cyber Manager of
     * @throws RejectedRequestException 403 {@code sod-conflict}; 409 {@code invalid-transition} when the VA Submission is
     *     not In Review; 400 {@code invalid-field} naming {@code decision}, {@code comment}, or {@code proposalId} or
     *     {@code justificationVersion}, which a batch review does not use; 409 {@code nothing-to-review} when no member
     *     awaits review
     */
    @Transactional
    public BatchReviewView reviewBatch(UUID batchId, ReviewRequest request) {
        UUID submissionId = findings.batchSubmission(batchId).orElseThrow(ResourceNotFoundException::new);
        AuditEvent refused = actor.attribute(AuditEvent.of(AuditAction.BATCH_REVIEWED, AuditOutcome.REFUSED))
                .with(DetailKey.BATCH_ID, batchId);
        Submissions.ReviewTarget target = submissions.lockForReview(submissionId, refused);
        ReviewFields.Parsed fields = ReviewFields.ofBatch(request);
        List<ReviewStore.Reviewed> decided = new ArrayList<>();
        List<BatchReviewView.LeftOut> leftOut = new ArrayList<>();
        for (ReviewStore.LockedMember member : store.lockBatch(target, batchId)) {
            if (!member.batchProposal().equals(member.currentProposal())) {
                leftOut.add(new BatchReviewView.LeftOut(member.findingId(), BatchReviewView.Why.PROPOSAL_CHANGED));
            } else if (member.reviewState() != ReviewState.PENDING) {
                leftOut.add(new BatchReviewView.LeftOut(member.findingId(), BatchReviewView.Why.REVIEWED));
            } else {
                decided.add(new ReviewStore.Reviewed(member.findingId(), member.batchProposal()));
            }
        }
        if (decided.isEmpty()) {
            audit.recordIndependently(target.refused().with(DetailKey.REASON, "nothing-to-review"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "nothing-to-review",
                    "No member of this batch awaits review: each was proposed again or reviewed since. Review them one "
                    + "by one.");
        }
        Instant now = clock.instant();
        UUID by = user.id();
        UUID batchReview = store.newBatchReview(target, batchId, by, now);
        store.reviewFindings(target, decided, fields, batchReview, by, now);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.BATCH_REVIEWED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId)
                .with(DetailKey.BATCH_ID, batchId).with(DetailKey.COUNT, decided.size())
                .with(DetailKey.DECISION, fields.decision().name()));
        decided.forEach(each -> recordFindingReviewed(target, each.findingId(), fields.decision(), batchId));
        return new BatchReviewView(batchReview, batchId, fields.decision(), fields.comment(), by, now,
                decided.stream().map(ReviewStore.Reviewed::findingId).toList(), leftOut);
    }

    /** The {@code FINDING_REVIEWED} entry of a review kept, made alone or in a batch review of {@code batchId}. */
    private void recordFindingReviewed(Submissions.ReviewTarget target, UUID findingId, ReviewDecision decision,
            @Nullable UUID batchId) {
        AuditEvent event = actor.attribute(AuditEvent.of(AuditAction.FINDING_REVIEWED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(target.submissionId()).onFinding(findingId)
                .with(DetailKey.DECISION, decision.name());
        audit.record(batchId == null ? event : event.with(DetailKey.BATCH_ID, batchId));
    }

    /**
     * Confirms or Disputes the justification of a Scan Report marked not applicable. The review keeps a copy of the
     * justification it was about, because the SMO/Tech Lead's rewrite overwrites it (ADR 0030 item 3) and puts the review
     * back to PENDING (decision 2026-10-24). The request names the version of the justification the Cyber Manager read, and
     * it must still be the current one, so a screen read before a rewrite never confirms the rewrite. The caller is
     * recorded at the reviewing step.
     *
     * @throws ResourceNotFoundException for a VA Submission of a System the caller is not Cyber Manager of
     * @throws RejectedRequestException 403 {@code sod-conflict}; 409 {@code invalid-transition} when the VA Submission is
     *     not In Review; 400 {@code invalid-field} naming {@code decision}, {@code comment} or {@code proposalId} (not
     *     used here) or {@code justificationVersion}; 409 {@code not-reviewable} when the Scan Report is not marked not
     *     applicable; 409 {@code justification-changed} when the version named is not the current one
     */
    @Transactional
    public ReviewView reviewReport(UUID submissionId, ReportType type, ReviewRequest request) {
        AuditEvent refused = actor.attribute(AuditEvent.of(AuditAction.REPORT_REVIEWED, AuditOutcome.REFUSED))
                .with(DetailKey.REPORT_TYPE, type.name());
        Submissions.ReviewTarget target = submissions.lockForReview(submissionId, refused);
        ReviewFields.Parsed fields = ReviewFields.ofJustification(request);
        // A Scan Report with no row was never written to, so it is uploaded: nothing to review either way.
        Optional<ReviewStore.LockedSlot> slot = store.lockSlot(target, type);
        if (slot.isEmpty() || slot.get().mode() != ReportMode.NOT_APPLICABLE) {
            audit.recordIndependently(target.refused().with(DetailKey.REASON, "not-reviewable"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "not-reviewable",
                    "Only a Scan Report marked not applicable has a justification to review; its Findings are "
                    + "reviewed one by one.");
        }
        if (!Objects.equals(slot.get().justificationVersion(), fields.justificationVersion())) {
            audit.recordIndependently(target.refused().with(DetailKey.REASON, "justification-changed"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "justification-changed",
                    "The justification reviewed has changed since it was read: read it again before reviewing.");
        }
        Instant now = clock.instant();
        ReviewView review = store.reviewReport(target, type, fields, user.id(), now);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.REPORT_REVIEWED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId)
                .with(DetailKey.REPORT_TYPE, type.name()).with(DetailKey.DECISION, fields.decision().name()));
        return review;
    }
}
