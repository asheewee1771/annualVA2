package sg.annualva.finding;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One proposal of a Finding Status, as the list and the history read it (stories 28 and 35). The texts are what the
 * SMO/Tech Lead typed: data, rendered only as text, never logged or audited. Only the fields of the status are set.
 *
 * @param id the proposal's own id
 * @param status what was proposed
 * @param evidence Remediated: what shows it was fixed
 * @param justification False Positive: why the Finding is not real
 * @param reason Risk Accepted: why the risk is accepted
 * @param approverName Risk Accepted: who accepted it, as free text
 * @param approverRole Risk Accepted: their role, as free text
 * @param approvalMemoFileId Risk Accepted, optional: the supporting evidence file of the VA Submission that is the approval
 *     memo; it is downloaded through the file endpoint, like every evidence file
 * @param plan Remediation Planned: the plan
 * @param targetDate Remediation Planned: the business date (Asia/Singapore) the fix is planned for
 * @param proposedAt when it was proposed (UTC)
 * @param batchId the Bulk-set batch it was proposed in (story 37); null for a proposal made for this Finding alone
 */
public record ProposalView(
        UUID id, FindingStatus status, @Nullable String evidence, @Nullable String justification,
        @Nullable String reason, @Nullable String approverName, @Nullable String approverRole,
        @Nullable UUID approvalMemoFileId, @Nullable String plan, @Nullable LocalDate targetDate, Instant proposedAt,
        @Nullable UUID batchId) {}
