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
import { useReviewSession } from "./reviewSession.ts";

/** How many Findings one page shows. */
const PAGE_SIZE = 100;

/** One Finding: what it is, what was proposed, its review, and the Confirm and Dispute controls. */
function FindingReviewRow({ finding }: { finding: Finding }) {
  const { reviewable, classification, reviewed } = useReviewSession();
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

/** The Findings proposed one by one: those whose current proposal was made in no batch (or that have none). */
export const NOT_IN_A_BATCH: FindingFilter = { batched: false };

/** The Findings whose proposal changed since their last review, in a batch or not (story 56). */
export const CHANGED_SINCE_LAST_REVIEW: FindingFilter = { changedSinceLastReview: true };

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
  const { submissionId, refreshKey } = useReviewSession();
  const [page, setPage] = useState(0);
  const load = useCallback(() => fetchFindings(submissionId, filter, page, PAGE_SIZE), [submissionId, filter, page]);
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
