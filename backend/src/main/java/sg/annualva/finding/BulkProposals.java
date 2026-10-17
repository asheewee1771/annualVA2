package sg.annualva.finding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.submission.Submissions;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * Bulk-set (story 37, ADR 0028): the SMO/Tech Lead applies one Finding Status, with one set of its fields, to several
 * Findings of a VA Submission in one step. The step is one batch; each Finding gets an ordinary proposal that names it and
 * becomes its current one, with the review back to PENDING, exactly as {@link Findings#propose} does for one Finding. The
 * Findings are refused as a whole: either every one named is a Finding of this VA Submission and every one gets the
 * proposal, or none does.
 */
@Service
public class BulkProposals {

    /** At most this many Findings in one batch: a bound on the request (as-1), well above the 100 to 200 a screen holds. */
    static final int MAX_FINDINGS = 1000;

    private static final String FINDING_IDS = "findingIds";

    private final Proposals proposals;
    private final AuditActor actor;
    private final AuditLog audit;

    BulkProposals(Proposals proposals, AuditActor actor, AuditLog audit) {
        this.proposals = proposals;
        this.actor = actor;
        this.audit = audit;
    }

    /**
     * Applies the status to every Finding named. The checks run in this order: ownership (a VA Submission of a System the
     * caller is not SMO/Tech Lead of is "not found", ADR 0013), the state of the VA Submission (its refusal is audited in an
     * entry of its own, ADR 0014), the list of ids, the status and its fields, the memo, and last that every Finding named
     * is one of this VA Submission. Only then is anything written, in one transaction with one {@code BATCH_PROPOSED}
     * entry for the step and one {@code STATUS_PROPOSED} entry per Finding carrying the batch id. A 400 writes nothing
     * and is not audited. Every step but the list of ids and the batch row is the one a proposal for one Finding runs
     * ({@link Proposals}).
     *
     * @throws ResourceNotFoundException for a VA Submission that does not exist or of a System the caller is not SMO/Tech
     *     Lead of
     * @throws RejectedRequestException 409 {@code invalid-transition} when the VA Submission is not in Draft or Returned
     *     for Rework; 400 {@code invalid-field} naming {@code findingIds} when the list is missing, empty, too long, holds
     *     something that is not an id or an id twice, or names anything that is not a Finding of this VA Submission
     *     (which one is never said), and naming the field when the status or a field of it is wrong, as for one Finding
     */
    @Transactional
    public BatchView propose(UUID submissionId, BulkProposalRequest request) {
        Submissions.FileTarget target = proposals.target(submissionId, AuditAction.BATCH_PROPOSED, null);
        List<UUID> ids = findingIds(request.findingIds());
        ProposalFields.Parsed fields = proposals.fields(target, request.proposal());
        if (!proposals.lock(target, ids)) {
            throw refused("must name only Findings of this VA Submission.");
        }
        UUID batch = proposals.newBatch(target);
        AuditEvent event = actor.attribute(AuditEvent.of(AuditAction.BATCH_PROPOSED, AuditOutcome.SUCCESS))
                .onSystem(target.systemId()).onSubmission(submissionId)
                .with(DetailKey.BATCH_ID, batch)
                .with(DetailKey.COUNT, ids.size())
                .with(DetailKey.FINDING_STATUS, fields.status().name());
        UUID memo = fields.approvalMemoFileId();
        audit.record(memo == null ? event : event.with(DetailKey.FILE_ID, memo));
        proposals.write(target, ids, fields, batch);
        return new BatchView(batch, ids.size());
    }

    /** The ids in the order given: at least one, at most {@link #MAX_FINDINGS}, each an id and each once. */
    private static List<UUID> findingIds(@Nullable List<@Nullable String> raw) {
        if (raw == null) {
            throw refused("is required.");
        }
        if (raw.isEmpty()) {
            throw refused("must name at least one Finding.");
        }
        if (raw.size() > MAX_FINDINGS) {
            throw refused("must name at most " + MAX_FINDINGS + " Findings.");
        }
        List<UUID> ids = new ArrayList<>(raw.size());
        Set<UUID> seen = new HashSet<>();
        for (String id : raw) {
            if (id == null || !ProposalFields.ID.matcher(id).matches()) {
                throw refused("must be a list of Finding ids.");
            }
            UUID parsed = UUID.fromString(id);
            if (!seen.add(parsed)) {
                throw refused("must name each Finding once.");
            }
            ids.add(parsed);
        }
        return ids;
    }

    private static RejectedRequestException refused(String what) {
        return RejectedRequestException.invalidField(FINDING_IDS, "The field '" + FINDING_IDS + "' " + what);
    }
}
