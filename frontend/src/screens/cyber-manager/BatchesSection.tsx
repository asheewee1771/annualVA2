import { useCallback, useState } from "react";
import {
  fetchBatches,
  reviewBatch,
  type BatchReview,
  type BatchSummary,
  type ReviewInput,
} from "../../api/findings.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { proposalDetail, statusSpec } from "../shared/findingStatuses.ts";
import { ReviewFindingsList } from "./ReviewFindingsList.tsx";
import { ReviewForm } from "./ReviewForm.tsx";
import { useReviewSession } from "./reviewSession.ts";

/** What a batch review left out, in words: the members changed since the batch, to review one by one. */
function LeftOutNote({ review }: { review: BatchReview }) {
  const proposedAgain = review.leftOut.filter((member) => member.reason === "PROPOSAL_CHANGED").length;
  const reviewed = review.leftOut.length - proposedAgain;
  return (
    <p role="status" className="muted">
      {review.reviewed.length} Findings decided. Left out because they changed since the batch: {proposedAgain} proposed
      again and {reviewed} already reviewed on their own. Review those one by one.
    </p>
  );
}

/**
 * The members still in a batch, collapsed until the Cyber Manager opens them, so the screen reads a batch's members only
 * when they are looked at.
 */
function BatchMembers({ batch, subject }: { batch: BatchSummary; subject: string }) {
  const [open, setOpen] = useState(false);
  const still = batch.awaitingReview + batch.confirmed + batch.disputed;
  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>Show the {still} Findings still in this batch</summary>
      {open ? (
        <ReviewFindingsList
          batchId={batch.batchId}
          heading="Findings still in this batch"
          caption={`The Findings of ${subject}, the most severe first`}
        />
      ) : null}
    </details>
  );
}

/**
 * One Bulk-set batch: what every member was proposed, where its members stand (with the Manually Entered ones counted,
 * story 52), Confirm batch and Dispute batch for every member still awaiting review, and its members, each with its own
 * controls.
 */
function BatchGroup({ batch, position }: { batch: BatchSummary; position: number }) {
  const { reviewable, classification, reviewed } = useReviewSession();
  const [lastReview, setLastReview] = useState<BatchReview | undefined>(undefined);
  const subject = `Bulk-set batch ${position}`;
  const send = useCallback(
    async (input: ReviewInput) => {
      const review = await reviewBatch(batch.batchId, input);
      setLastReview(review);
      return review;
    },
    [batch.batchId],
  );
  return (
    <div className="review-item">
      <h4>
        {subject}: {statusSpec(batch.proposal.status).label} for {batch.count} Findings
      </h4>
      <p className="finding-detail">{proposalDetail(batch.proposal)}</p>
      <p className="muted">
        {batch.awaitingReview} awaiting review · {batch.confirmed} confirmed · {batch.disputed} Disputed ·{" "}
        {batch.changedSince} changed since the batch (shown with their current proposal)
      </p>
      {batch.manuallyEntered > 0 ? (
        <p>
          <span className="flag flag--warning">Manually Entered</span> {batch.manuallyEntered} of its Findings were
          entered by hand: check them before deciding the batch.
        </p>
      ) : null}
      {reviewable && batch.awaitingReview > 0 ? (
        <ReviewForm
          idPrefix={`batch-${batch.batchId}`}
          subject={`${subject}, its ${batch.awaitingReview} Findings awaiting review`}
          classification={classification}
          send={send}
          onReviewed={reviewed}
          actionSuffix=" batch"
        />
      ) : null}
      {lastReview && lastReview.leftOut.length > 0 ? <LeftOutNote review={lastReview} /> : null}
      <BatchMembers batch={batch} subject={subject} />
    </div>
  );
}

/** The Bulk-set batches of the VA Submission (story 51), in the order they were made, each with its members under it. */
export function BatchesSection() {
  const { submissionId, refreshKey } = useReviewSession();
  const load = useCallback(() => fetchBatches(submissionId), [submissionId]);
  const batches = useResource(load, refreshKey);
  const list = batches.data ?? [];
  return (
    <section className="card" aria-labelledby="review-batches">
      <h3 id="review-batches">Bulk-set batches ({list.length})</h3>
      {batches.error ? <ErrorPanel problem={batches.error.problem} /> : null}
      {batches.data && list.length === 0 ? <p className="muted">No Findings were Bulk-set.</p> : null}
      {list.map((batch, index) => (
        <BatchGroup key={batch.batchId} batch={batch} position={index + 1} />
      ))}
    </section>
  );
}
