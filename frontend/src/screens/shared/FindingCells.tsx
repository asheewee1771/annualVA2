import type { Finding } from "../../api/findings.ts";
import { proposalDetail, REVIEW_LABELS, SEVERITY_LABELS, severityClass, statusSpec } from "./findingStatuses.ts";
import { LatestReview } from "./LatestReview.tsx";
import { reportTitle } from "./scanReports.ts";

/**
 * The cells every reader of a VA Submission's Findings shows in the same order: the severity, what the Finding is
 * (Manually Entered ones flagged, story 52), and what was proposed for it. Everything in them is text.
 */
export function FindingCells({ finding }: { finding: Finding }) {
  const proposal = finding.currentProposal;
  return (
    <>
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
    </>
  );
}

/** A Finding's review state and its latest review, marked as earlier when it was of another proposal. */
export function FindingReview({ finding }: { finding: Finding }) {
  const latest = finding.latestReview;
  return (
    <>
      {finding.reviewState ? <strong>{REVIEW_LABELS[finding.reviewState]}</strong> : null}
      {latest ? <LatestReview review={latest} current={latest.proposalId === finding.currentProposal?.id} /> : null}
    </>
  );
}
