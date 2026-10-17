package sg.annualva.finding;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The body of a Bulk-set (story 37): the Findings to apply it to, then one status and its fields exactly as for one
 * Finding ({@link ProposalRequest}). Every id is read as text, so an id that cannot be read is refused by name rather than
 * failing the whole body.
 *
 * @param findingIds the Findings of this VA Submission to apply it to: at least one, at most
 *     {@value BulkProposals#MAX_FINDINGS}, each once
 */
public record BulkProposalRequest(
        @Nullable List<@Nullable String> findingIds, @Nullable String status, @Nullable String evidence,
        @Nullable String justification, @Nullable String reason, @Nullable String approverName,
        @Nullable String approverRole, @Nullable String approvalMemoFileId, @Nullable String plan,
        @Nullable String targetDate) {

    /** The status and its fields, as a proposal for one Finding carries them. */
    ProposalRequest proposal() {
        return new ProposalRequest(status, evidence, justification, reason, approverName, approverRole,
                approvalMemoFileId, plan, targetDate);
    }
}
