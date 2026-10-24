import type { Review } from "../../api/findings.ts";

const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });

const DECISIONS = { CONFIRMED: "Confirmed", DISPUTED: "Disputed" } as const;

/**
 * The latest review of an item, as text: what was decided and when, the comment, and, when the review was of something
 * that has since changed (an earlier proposal, or a justification rewritten since), that it was, with the reviewed
 * wording of a justification.
 */
export function LatestReview({ review, current }: { review: Review; current: boolean }) {
  return (
    <div className="latest-review">
      <p className="muted">
        {current ? "Latest review" : "Earlier review"}: {DECISIONS[review.decision]} on{" "}
        {WHEN.format(new Date(review.reviewedAt))}
        {current ? "" : " (of what was there before the latest change)"}
      </p>
      {review.comment !== null ? <p className="finding-detail">{review.comment}</p> : null}
      {!current && review.justification !== null ? (
        <p className="muted finding-detail">Wording reviewed then: {review.justification}</p>
      ) : null}
    </div>
  );
}
