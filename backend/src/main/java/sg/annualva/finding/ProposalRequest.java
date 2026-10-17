package sg.annualva.finding;

import org.jspecify.annotations.Nullable;

/**
 * The body of a proposal, every field as text so that a field that cannot be read is named in the answer rather than
 * failing the whole body. Only the fields of the status are allowed; {@link ProposalFields} reads and bounds them.
 *
 * @param status REMEDIATED, FALSE_POSITIVE, RISK_ACCEPTED or REMEDIATION_PLANNED
 * @param evidence Remediated: required
 * @param justification False Positive: required
 * @param reason Risk Accepted: required
 * @param approverName Risk Accepted: required, free text
 * @param approverRole Risk Accepted: required, free text
 * @param approvalMemoFileId Risk Accepted: optional; the id of a supporting evidence file of the same VA Submission that
 *     has finished its checks
 * @param plan Remediation Planned: required
 * @param targetDate Remediation Planned: required; a future business date, {@code yyyy-mm-dd}
 */
public record ProposalRequest(
        @Nullable String status, @Nullable String evidence, @Nullable String justification, @Nullable String reason,
        @Nullable String approverName, @Nullable String approverRole, @Nullable String approvalMemoFileId,
        @Nullable String plan, @Nullable String targetDate) {}
