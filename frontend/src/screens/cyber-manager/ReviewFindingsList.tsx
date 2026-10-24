import { useCallback } from "react";
import { fetchFindings, reviewFinding, type Finding, type FindingFilter } from "../../api/findings.ts";
import { FindingReview } from "../shared/FindingCells.tsx";
import { FINDINGS_PAGE_SIZE, FindingsList } from "../shared/FindingsList.tsx";
import { ReviewForm } from "./ReviewForm.tsx";
import { useReviewSession, type ReviewSession } from "./reviewSession.ts";

/** A Finding's review, and Confirm and Dispute while the VA Submission takes reviews. */
function FindingReviewCell({ finding, session }: { finding: Finding; session: ReviewSession }) {
  const proposal = finding.currentProposal;
  return (
    <>
      <FindingReview finding={finding} />
      {session.reviewable && proposal ? (
        <ReviewForm
          idPrefix={`review-${finding.id}`}
          subject={finding.identifier}
          classification={session.classification}
          send={(input) => reviewFinding(finding.id, proposal.id, input)}
          onReviewed={session.reviewed}
        />
      ) : null}
    </>
  );
}

/** The Findings proposed one by one: those whose current proposal was made in no batch (or that have none). */
export const NOT_IN_A_BATCH: FindingFilter = { batched: false };

/** The Findings whose proposal changed since their last review, in a batch or not (story 56). */
export const CHANGED_SINCE_LAST_REVIEW: FindingFilter = { changedSinceLastReview: true };

/**
 * A page at a time of Findings to review, the most severe first, each with its own Confirm and Dispute, narrowed by the
 * server to the filter: a batch's members still in it, the Findings not in a batch, or those changed since their last
 * review. It is read again on every refresh of the review screen.
 */
export function ReviewFindingsList({
  filter,
  heading,
  caption,
}: {
  /** What the list is narrowed to; kept the same object between renders, so the list is read only when it changes. */
  filter: FindingFilter;
  /** The heading, followed by how many Findings there are. */
  heading: string;
  caption: string;
}) {
  const session = useReviewSession();
  const { submissionId } = session;
  const load = useCallback(
    (page: number) => fetchFindings(submissionId, filter, page, FINDINGS_PAGE_SIZE),
    [submissionId, filter],
  );
  return (
    <FindingsList
      load={load}
      refreshKey={session.refreshKey}
      heading={heading}
      caption={caption}
      reviewCell={(finding) => <FindingReviewCell finding={finding} session={session} />}
    />
  );
}
