package sg.annualva.finding;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.AuditActor;
import sg.annualva.access.Need;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.submission.Submissions;

/**
 * The rework queue (stories 60 and 61): the Findings of a VA Submission its SMO/Tech Lead still has to act on, one stage
 * after another ({@link ReworkStage}). It reads the same rule as the Findings list's "needs attention" (no proposal yet,
 * or a Disputed one), so a Finding leaves the queue as soon as a new proposal is made for it, whose review is then PENDING
 * again. It is the SMO/Tech Lead's own work list, so only they read it.
 */
@Service
public class ReworkQueues {

    private final ReworkQueueStore queue;
    private final FindingStore findings;
    private final Submissions submissions;
    private final AccessGuard guard;
    private final AuditActor actor;
    private final AuditLog audit;

    ReworkQueues(ReworkQueueStore queue, FindingStore findings, Submissions submissions, AccessGuard guard,
            AuditActor actor, AuditLog audit) {
        this.queue = queue;
        this.findings = findings;
        this.submissions = submissions;
        this.guard = guard;
        this.actor = actor;
        this.audit = audit;
    }

    /**
     * One page of the queue of a VA Submission of a System the caller is SMO/Tech Lead of, in any state (the screen offers
     * it once the VA Submission is Returned for Rework). Any other VA Submission is "not found", the same as one that does
     * not exist (ADR 0013). It carries what the Cyber Manager and the SMO/Tech Lead wrote, so every read is audited as
     * {@code REWORK_QUEUE_VIEWED} with the number of Findings returned, never their text.
     */
    @Transactional
    public ReworkQueue queue(UUID submissionId, int page, int size) {
        UUID systemId = submissions.systemOf(submissionId);
        guard.requireSystem(systemId, Need.SUBMIT);
        List<ReworkQueueStore.Queued> queued = queue.page(submissionId, size, (long) page * size);
        Map<UUID, List<FindingView.SourceFile>> sources =
                findings.sources(queued.stream().map(each -> each.row().id()).toList());
        List<ReworkQueue.Item> items = queued.stream()
                .map(each -> ReworkQueue.Item.of(each.stage(), FindingView.of(each.row(), sources)))
                .toList();
        Map<ReworkStage, Long> counts = queue.counts(submissionId);
        audit.record(actor.attribute(AuditEvent.of(AuditAction.REWORK_QUEUE_VIEWED, AuditOutcome.SUCCESS))
                .onSystem(systemId).onSubmission(submissionId).with(DetailKey.COUNT, items.size()));
        return new ReworkQueue(
                counts.entrySet().stream().map(count -> new ReworkQueue.StageCount(count.getKey(), count.getValue()))
                        .toList(),
                items, page, size, counts.values().stream().mapToLong(Long::longValue).sum());
    }
}
