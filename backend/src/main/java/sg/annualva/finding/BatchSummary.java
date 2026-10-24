package sg.annualva.finding;

import java.time.Instant;
import java.util.UUID;

/**
 * A Bulk-set batch as the review screen lists it (story 51): what was proposed, by whom and when, and where its members
 * stand now. A member is still in the batch while its current proposal is the one the batch made; one proposed again since
 * (or left with no proposal by a rescan) is counted as changed since and is reviewed on its own.
 *
 * @param batchId the batch
 * @param proposedBy who made it (an id, never a name or email)
 * @param proposedAt when (UTC)
 * @param proposal what every member was proposed: the proposal of its first member, whose status and fields are those of
 *     every member (ADR 0028 item 1); its {@code id} is that member's proposal. The texts are what the SMO/Tech Lead typed:
 *     data, rendered only as text
 * @param count how many Findings the batch was applied to
 * @param awaitingReview members still in the batch whose review is PENDING: what a batch review would decide
 * @param confirmed members still in the batch that the Cyber Manager confirmed
 * @param disputed members still in the batch that the Cyber Manager Disputed
 * @param changedSince members whose current proposal is no longer the batch's
 * @param manuallyEntered members still in the batch that are Manually Entered Findings (story 52): the screen marks the
 *     batch, so one Confirm batch never decides one unseen
 */
public record BatchSummary(
        UUID batchId, UUID proposedBy, Instant proposedAt, ProposalView proposal, int count, int awaitingReview,
        int confirmed, int disputed, int changedSince, int manuallyEntered) {}
