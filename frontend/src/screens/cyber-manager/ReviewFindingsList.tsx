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
import {
  proposalDetail,
  REVIEW_LABELS,
  SEVERITY_LABELS,
  severityClass,
  statusSpec,
} from "../shared/findingStatuses.ts";
import { Pager } from "../shared/Pager.tsx";
import { reportTitle } from "../shared/scanReports.ts";
import { LatestReview } from "./LatestReview.tsx";
import { ReviewForm } from "./ReviewForm.tsx";

/** How many Findings one page shows. */
const PAGE_SIZE = 100;

/** One Finding: what it is, what was proposed, its review, and the Confirm and Dispute controls. */
function FindingReviewRow({
  finding,
  reviewable,
  classification,
  onReviewed,
}: {
  finding: Finding;
  reviewable: boolean;
  classification: string;
  onReviewed: () => void;
}) {
  const proposal = finding.currentProposal;
  const latest = finding.latestReview;
  return (
    <tr>
      <td>
        <span className={severityClass(finding.severity)}>{SEVERITY_LABELS[finding.severity]}</span>
      </td>
      <td className="mono">
        {finding.identifier}
        {finding.source === "MANUAL" ? (
          <>
            {" "}
            <span className="flag flag--warning">Manually Entered</span>
          </>
        ) : null}
        {finding.title !== null ? <p className="muted finding-detail">{finding.title}</p> : null}
        <p className="muted finding-detail">{finding.packageOrRule}</p>
        <p className="muted">{reportTitle(finding.reportType)}</p>
      </td>
      <td>
        {proposal ? (
          <>
            <strong>{statusSpec(proposal.status).label}</strong>
            {proposal.batchId ? <span className="muted"> · Bulk-set</span> : null}
            {proposal.proposedAs === "RESCAN" ? <span className="muted"> · Rescan</span> : null}
            <p className="muted finding-detail">{proposalDetail(proposal)}</p>
          </>
        ) : (
          <span className="flag flag--warning">No status proposed</span>
        )}
      </td>
      <td>
        {finding.reviewState ? <strong>{REVIEW_LABELS[finding.reviewState]}</strong> : null}
        {latest ? <LatestReview review={latest} current={latest.proposalId === proposal?.id} /> : null}
        {reviewable && proposal ? (
          <ReviewForm
            idPrefix={`review-${finding.id}`}
            subject={finding.identifier}
            classification={classification}
            send={(input) => reviewFinding(finding.id, proposal.id, input)}
            onReviewed={onReviewed}
          />
        ) : null}
      </td>
    </tr>
  );
}

/** A table of Findings, each with what was proposed, its review and its own Confirm and Dispute. */
function FindingsReviewTable({
  findings,
  caption,
  reviewable,
  classification,
  onReviewed,
}: {
  findings: readonly Finding[];
  caption: string;
  reviewable: boolean;
  classification: string;
  onReviewed: () => void;
}) {
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
            <FindingReviewRow
              key={finding.id}
              finding={finding}
              reviewable={reviewable}
              classification={classification}
              onReviewed={onReviewed}
            />
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

/** One page of the list: the table and the pager, or that there is nothing to review. */
function FindingsPageView({
  result,
  caption,
  reviewable,
  classification,
  onReviewed,
  onPage,
}: {
  result: FindingPage;
  caption: string;
  reviewable: boolean;
  classification: string;
  onReviewed: () => void;
  onPage: (page: number) => void;
}) {
  if (result.items.length === 0) return <p className="muted">There are none.</p>;
  return (
    <>
      <FindingsReviewTable
        findings={result.items}
        caption={caption}
        reviewable={reviewable}
        classification={classification}
        onReviewed={onReviewed}
      />
      {result.total > PAGE_SIZE ? (
        <Pager page={result.page} pages={Math.ceil(result.total / PAGE_SIZE)} total={result.total} onPage={onPage} />
      ) : null}
    </>
  );
}

/**
 * A page at a time of Findings to review, the most severe first, each with its own Confirm and Dispute: the members still
 * in one Bulk-set batch, or, with no batch, every Finding whose current proposal was made alone (or that has none).
 * `version` changes after every review on the screen, and the page is read again then.
 */
export function ReviewFindingsList({
  submissionId,
  batchId,
  heading,
  caption,
  reviewable,
  classification,
  version,
  onReviewed,
}: {
  submissionId: string;
  batchId: string | null;
  /** The heading, followed by how many Findings there are. */
  heading: string;
  caption: string;
  reviewable: boolean;
  classification: string;
  version: number;
  onReviewed: () => void;
}) {
  const [page, setPage] = useState(0);
  const load = useCallback(
    () => fetchFindings(submissionId, filterOf(batchId), page, PAGE_SIZE),
    // version is a dependency so that a review anywhere on the screen reads the page again.
    [submissionId, batchId, page, version],
  );
  const findings = useResource(load);
  const result = findings.data;
  return (
    <>
      <p>
        <strong>
          {heading} ({result ? result.total : 0})
        </strong>
      </p>
      {findings.error ? <ErrorPanel problem={findings.error.problem} /> : null}
      {result ? (
        <FindingsPageView
          result={result}
          caption={caption}
          reviewable={reviewable}
          classification={classification}
          onReviewed={onReviewed}
          onPage={setPage}
        />
      ) : (
        <p className="muted">Loading the Findings…</p>
      )}
    </>
  );
}
