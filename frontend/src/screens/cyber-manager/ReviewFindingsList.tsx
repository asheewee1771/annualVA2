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

/** The list narrowed to a batch's members still in it, or, with no batch, to the Findings in no batch. */
function filterOf(batchId: string | null): FindingFilter {
  return batchId === null ? { batched: false } : { batchId };
}

/**
 * A page at a time of Findings to review, the most severe first, each with its own Confirm and Dispute: the members still
 * in one Bulk-set batch, or, with no batch, every Finding whose current proposal was made alone (or that has none). It
 * is read again on every refresh of the review screen.
 */
export function ReviewFindingsList({
  batchId,
  heading,
  caption,
}: {
  batchId: string | null;
  /** The heading, followed by how many Findings there are. */
  heading: string;
  caption: string;
}) {
  const session = useReviewSession();
  const { submissionId } = session;
  const load = useCallback(
    (page: number) => fetchFindings(submissionId, filterOf(batchId), page, FINDINGS_PAGE_SIZE),
    [submissionId, batchId],
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
