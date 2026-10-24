import { useCallback, useState } from "react";
import {
  fetchFindings,
  reviewFinding,
  type Finding,
  type FindingFilter,
  type FindingPage,
} from "../../api/findings.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { FindingCells, FindingReview } from "../shared/FindingCells.tsx";
import { Pager } from "../shared/Pager.tsx";
import { ReviewForm } from "./ReviewForm.tsx";
import { useReviewSession } from "./reviewSession.ts";

/** How many Findings one page shows. */
const PAGE_SIZE = 100;

/** One Finding: what it is, what was proposed, its review, and the Confirm and Dispute controls. */
function FindingReviewRow({ finding }: { finding: Finding }) {
  const { reviewable, classification, reviewed } = useReviewSession();
  const proposal = finding.currentProposal;
  return (
    <tr>
      <FindingCells finding={finding} />
      <td>
        <FindingReview finding={finding} />
        {reviewable && proposal ? (
          <ReviewForm
            idPrefix={`review-${finding.id}`}
            subject={finding.identifier}
            classification={classification}
            send={(input) => reviewFinding(finding.id, proposal.id, input)}
            onReviewed={reviewed}
          />
        ) : null}
      </td>
    </tr>
  );
}

/** A table of Findings, each with what was proposed, its review and its own Confirm and Dispute. */
function FindingsReviewTable({ findings, caption }: { findings: readonly Finding[]; caption: string }) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Severity</th>
            <th scope="col">Finding</th>
            <th scope="col">Proposed status</th>
            <th scope="col">Review</th>
          </tr>
        </thead>
        <tbody>
          {findings.map((finding) => (
            <FindingReviewRow key={finding.id} finding={finding} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** The list narrowed to a batch's members still in it, or, with no batch, to the Findings in no batch. */
function filterOf(batchId: string | null): FindingFilter {
  return batchId === null ? { batched: false } : { batchId };
}

/**
 * One page of the list: the table, or that this page has none, and the pager whenever there is more than one page (so a
 * page left empty when the list shrank after a review can still be left).
 */
function FindingsPageView({
  result,
  caption,
  onPage,
}: {
  result: FindingPage;
  caption: string;
  onPage: (page: number) => void;
}) {
  return (
    <>
      {result.items.length === 0 ? (
        <p className="muted">There are none here.</p>
      ) : (
        <FindingsReviewTable findings={result.items} caption={caption} />
      )}
      {result.total > PAGE_SIZE ? (
        <Pager page={result.page} pages={Math.ceil(result.total / PAGE_SIZE)} total={result.total} onPage={onPage} />
      ) : null}
    </>
  );
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
  const { submissionId, refreshKey } = useReviewSession();
  const [page, setPage] = useState(0);
  const load = useCallback(
    () => fetchFindings(submissionId, filterOf(batchId), page, PAGE_SIZE),
    [submissionId, batchId, page],
  );
  const findings = useResource(load, refreshKey);
  const result = findings.data;
  return (
    <>
      <p>
        <strong>
          {heading} ({result ? result.total : 0})
        </strong>
      </p>
      {findings.error ? <ErrorPanel problem={findings.error.problem} /> : null}
      {findings.loading && !result ? <p className="muted">Loading the Findings…</p> : null}
      {result ? <FindingsPageView result={result} caption={caption} onPage={setPage} /> : null}
    </>
  );
}
