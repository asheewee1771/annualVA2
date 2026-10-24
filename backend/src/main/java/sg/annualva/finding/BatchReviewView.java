package sg.annualva.finding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One review of a Bulk-set batch by a Cyber Manager (story 51): the decision and comment every member it decided now
 * carries in its own review, and the members it left out because they changed since the batch. The comment is what a
 * person wrote: data, rendered only as text, never logged or audited.
 *
 * @param id the batch review's own id
 * @param batchId the batch reviewed
 * @param decision confirmed or Disputed
 * @param comment what the Cyber Manager wrote; always present on a Dispute, null on a confirmation without one
 * @param reviewedBy who reviewed (an id, never a name or email)
 * @param reviewedAt when (UTC)
 * @param reviewed the Findings it decided, in id order: each member whose current proposal is the batch's and was awaiting
 *     review
 * @param leftOut the members it did not decide, in id order, each with why
 */
public record BatchReviewView(
        UUID id, UUID batchId, ReviewDecision decision, @Nullable String comment, UUID reviewedBy, Instant reviewedAt,
        List<UUID> reviewed, List<LeftOut> leftOut) {

    /** A member of the batch that the batch review left alone, to be reviewed on its own. */
    public record LeftOut(UUID findingId, Why reason) {}

    /** Why a member was left out of a batch review. */
    public enum Why {
        /** Its current proposal is no longer the batch's: it was proposed again, or a rescan withdrew it. */
        PROPOSAL_CHANGED,
        /** Its current proposal is the batch's but was already confirmed or Disputed. */
        REVIEWED
    }
}
