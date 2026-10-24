import { useCallback, useState } from "react";
import { fetchScanReportModes, reviewScanReport, type ScanReportMode } from "../../api/scanReports.ts";
import { fetchSubmission, type Submission } from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { REVIEW_LABELS } from "../shared/findingStatuses.ts";
import { reportTitle } from "../shared/scanReports.ts";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { BatchesSection } from "./BatchesSection.tsx";
import { LatestReview } from "./LatestReview.tsx";
import { CM_INBOX_PATH } from "./paths.ts";
import { ReviewFindingsList } from "./ReviewFindingsList.tsx";
import { ReviewForm } from "./ReviewForm.tsx";

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
 * The Cyber Manager's review of one VA Submission (story 50): the not-applicable justifications, the Bulk-set batches with
 * their members grouped under them and Confirm batch or Dispute batch for each (story 51), and every other Finding, each
 * with what was proposed and Confirm or Dispute with a comment. Manually Entered Findings and not-applicable Scan Reports
 * are clearly marked (story 52). The controls show only while it is In Review; the server decides every refusal, including
 * segregation of duties (story 58), and the screen shows its problem. Everything a person typed is shown as text.
 */
export function ReviewScreen({ submissionId, classification }: { submissionId: string; classification: string }) {
  const loadSubmission = useCallback(() => fetchSubmission(submissionId), [submissionId]);
  const loadReports = useCallback(() => fetchScanReportModes(submissionId), [submissionId]);
  const submission = useResource(loadSubmission);
  const reports = useResource(loadReports);
  const reviewable = submission.data?.state === "IN_REVIEW";
  const reloadReports = reports.reload;
  const reloadSubmission = submission.reload;
  // Goes up after every review, so the batches and every list of Findings are read again: a batch review changes its
  // members, and a member reviewed alone changes its batch's counts.
  const [version, setVersion] = useState(0);
  const reviewed = useCallback(() => {
    reloadReports();
    reloadSubmission();
    setVersion((n) => n + 1);
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
      <BatchesSection
        submissionId={submissionId}
        reviewable={reviewable}
        classification={classification}
        version={version}
        onReviewed={reviewed}
      />
      <section className="card" aria-labelledby="review-findings">
        <h3 id="review-findings">Findings proposed one by one</h3>
        <ReviewFindingsList
          submissionId={submissionId}
          batchId={null}
          heading="Findings not in a batch"
          caption="Findings to review one by one, the most severe first"
          reviewable={reviewable}
          classification={classification}
          version={version}
          onReviewed={reviewed}
        />
      </section>
    </div>
  );
}
