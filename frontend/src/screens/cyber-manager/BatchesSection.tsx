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
 * One Bulk-set batch: what every member was proposed, where its members stand, Confirm batch and Dispute batch for every
 * member still awaiting review, and the members still in the batch, each with its own controls.
 */
function BatchGroup({
  submissionId,
  batch,
  position,
  reviewable,
  classification,
  version,
  onReviewed,
}: {
  submissionId: string;
  batch: BatchSummary;
  position: number;
  reviewable: boolean;
  classification: string;
  version: number;
  onReviewed: () => void;
}) {
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
      {reviewable && batch.awaitingReview > 0 ? (
        <ReviewForm
          idPrefix={`batch-${batch.batchId}`}
          subject={`${subject}, its ${batch.awaitingReview} Findings awaiting review`}
          classification={classification}
          send={send}
          onReviewed={onReviewed}
          actionSuffix=" batch"
        />
      ) : null}
      {lastReview && lastReview.leftOut.length > 0 ? <LeftOutNote review={lastReview} /> : null}
      <ReviewFindingsList
        submissionId={submissionId}
        batchId={batch.batchId}
        heading="Findings still in this batch"
        caption={`The Findings of ${subject}, the most severe first`}
        reviewable={reviewable}
        classification={classification}
        version={version}
        onReviewed={onReviewed}
      />
    </div>
  );
}

/**
 * The Bulk-set batches of the VA Submission (story 51), in the order they were made, each with its members grouped under
 * it. `version` changes after every review on the screen, and the batches are read again then.
 */
export function BatchesSection({
  submissionId,
  reviewable,
  classification,
  version,
  onReviewed,
}: {
  submissionId: string;
  reviewable: boolean;
  classification: string;
  version: number;
  onReviewed: () => void;
}) {
  // version is a dependency so that a review anywhere on the screen reads the batches again.
  const load = useCallback(() => fetchBatches(submissionId), [submissionId, version]);
  const batches = useResource(load);
  const list = batches.data ?? [];
  return (
    <section className="card" aria-labelledby="review-batches">
      <h3 id="review-batches">Bulk-set batches ({list.length})</h3>
      {batches.error ? <ErrorPanel problem={batches.error.problem} /> : null}
      {batches.data && list.length === 0 ? <p className="muted">No Findings were Bulk-set.</p> : null}
      {list.map((batch, index) => (
        <BatchGroup
          key={batch.batchId}
          submissionId={submissionId}
          batch={batch}
          position={index + 1}
          reviewable={reviewable}
          classification={classification}
          version={version}
          onReviewed={onReviewed}
        />
      ))}
    </section>
  );
}
