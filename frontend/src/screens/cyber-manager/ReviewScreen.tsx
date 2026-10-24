import { useCallback, useState } from "react";
import { fetchFindings, reviewFinding, type Finding } from "../../api/findings.ts";
import { fetchScanReportModes, reviewScanReport, type ScanReportMode } from "../../api/scanReports.ts";
import { fetchSubmission, type Submission } from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
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
import { STATE_LABELS } from "../shared/systemStates.ts";
import { LatestReview } from "./LatestReview.tsx";
import { CM_INBOX_PATH } from "./paths.ts";
import { ReviewForm } from "./ReviewForm.tsx";
import { FinishReview } from "./FinishReview.tsx";

/** How many Findings one page shows. */
const PAGE_SIZE = 100;

/** The Scan Reports marked not applicable: each justification, clearly marked, with its review. */
function NotApplicableSection({
  submissionId,
  reports,
  reviewable,
  classification,
  onReviewed,
}: {
  submissionId: string;
  reports: readonly ScanReportMode[];
  reviewable: boolean;
  classification: string;
  onReviewed: () => void;
}) {
  const notApplicable = reports.filter((report) => report.mode === "NOT_APPLICABLE");
  return (
    <section className="card" aria-labelledby="review-not-applicable">
      <h3 id="review-not-applicable">Scan Reports marked not applicable ({notApplicable.length})</h3>
      {notApplicable.length === 0 ? <p className="muted">Every Scan Report was provided.</p> : null}
      {notApplicable.map((report) => {
        const latest = report.latestReview;
        return (
          <div key={report.reportType} className="review-item">
            <p>
              <strong>{reportTitle(report.reportType)}</strong>{" "}
              <span className="flag flag--warning">Not applicable</span>
              {report.reviewState ? <span className="muted"> · {REVIEW_LABELS[report.reviewState]}</span> : null}
            </p>
            <p className="finding-detail">{report.justification}</p>
            {latest ? (
              <LatestReview review={latest} current={latest.justificationVersion === report.justificationVersion} />
            ) : null}
            {reviewable ? (
              <ReviewForm
                idPrefix={`review-${report.reportType}`}
                subject={`the not-applicable justification of ${reportTitle(report.reportType)}`}
                classification={classification}
                send={(input) => reviewScanReport(submissionId, report.reportType, report.justificationVersion, input)}
                onReviewed={onReviewed}
              />
            ) : null}
          </div>
        );
      })}
    </section>
  );
}

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

/** Every Finding of the VA Submission, the most severe first, a page at a time, each with its review controls. */
function FindingsReviewSection({
  submissionId,
  reviewable,
  classification,
  onReviewed,
}: {
  submissionId: string;
  reviewable: boolean;
  classification: string;
  onReviewed: () => void;
}) {
  const [page, setPage] = useState(0);
  const load = useCallback(() => fetchFindings(submissionId, {}, page, PAGE_SIZE), [submissionId, page]);
  const findings = useResource(load);
  const reload = findings.reload;
  const reviewed = useCallback(() => {
    reload();
    onReviewed();
  }, [reload, onReviewed]);
  const items = findings.data?.items ?? [];
  const total = findings.data?.total ?? 0;

  return (
    <section className="card" aria-labelledby="review-findings">
      <h3 id="review-findings">Findings ({total})</h3>
      {findings.error ? <ErrorPanel problem={findings.error.problem} /> : null}
      {findings.loading && !findings.data ? <p className="muted">Loading the Findings…</p> : null}
      {findings.data && items.length === 0 ? <p className="muted">There are no Findings.</p> : null}
      {items.length > 0 ? (
        <div className="table-scroll">
          <table className="data-table">
            <caption className="visually-hidden">Findings to review, the most severe first</caption>
            <thead>
              <tr>
                <th scope="col">Severity</th>
                <th scope="col">Finding</th>
                <th scope="col">Proposed status</th>
                <th scope="col">Review</th>
              </tr>
            </thead>
            <tbody>
              {items.map((finding) => (
                <FindingReviewRow
                  key={finding.id}
                  finding={finding}
                  reviewable={reviewable}
                  classification={classification}
                  onReviewed={reviewed}
                />
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {total > PAGE_SIZE ? (
        <Pager page={page} pages={Math.ceil(total / PAGE_SIZE)} total={total} onPage={setPage} />
      ) : null}
    </section>
  );
}

/** Which Quarter the VA Submission is in, its state and the Overdue flag; and why there are no controls when not In Review. */
function SubmissionSummary({ submission }: { submission: Submission }) {
  return (
    <p className="muted">
      VA Cycle {submission.cycle}, Q{submission.quarter}: {STATE_LABELS[submission.state]}
      {submission.overdue ? (
        <>
          {" "}
          <span className="flag flag--danger">Overdue</span>
        </>
      ) : null}
      {submission.state === "IN_REVIEW" ? "" : ". It can be reviewed only while it is In Review."}
    </p>
  );
}

/**
 * The Cyber Manager's review of one VA Submission (story 50): the not-applicable justifications and every Finding, each
 * with what was proposed and Confirm or Dispute with a comment. Manually Entered Findings and not-applicable Scan Reports
 * are clearly marked (story 52). The controls show only while it is In Review; the server decides every refusal, including
 * segregation of duties (story 58), and the screen shows its problem. Everything a person typed is shown as text.
 */
export function ReviewScreen({ submissionId, classification }: { submissionId: string; classification: string }) {
  const loadSubmission = useCallback(() => fetchSubmission(submissionId), [submissionId]);
  const loadReports = useCallback(() => fetchScanReportModes(submissionId), [submissionId]);
  const submission = useResource(loadSubmission);
  const reports = useResource(loadReports);
  const [reviews, setReviews] = useState(0);
  const reviewable = submission.data?.state === "IN_REVIEW";
  const reloadReports = reports.reload;
  const reloadSubmission = submission.reload;
  const reviewed = useCallback(() => {
    reloadReports();
    reloadSubmission();
    setReviews((count) => count + 1);
  }, [reloadReports, reloadSubmission]);

  return (
    <div className="card">
      <p>
        <AppLink path={CM_INBOX_PATH}>Back to your inbox</AppLink>
      </p>
      <h2>Review VA Submission</h2>
      {submission.error ? <ErrorPanel problem={submission.error.problem} /> : null}
      {submission.data ? <SubmissionSummary submission={submission.data} /> : null}
      {reports.error ? <ErrorPanel problem={reports.error.problem} /> : null}
      {reports.data ? (
        <NotApplicableSection
          submissionId={submissionId}
          reports={reports.data}
          reviewable={reviewable}
          classification={classification}
          onReviewed={reviewed}
        />
      ) : null}
      <FindingsReviewSection
        submissionId={submissionId}
        reviewable={reviewable}
        classification={classification}
        onReviewed={reviewed}
      />
      {reviewable ? (
        <FinishReview
          submissionId={submissionId}
          reviews={reviews}
          classification={classification}
          onMoved={reviewed}
        />
      ) : null}
    </div>
  );
}
