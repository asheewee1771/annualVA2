import { useCallback, useMemo, useState } from "react";
import { fetchScanReportModes, reviewScanReport, type ScanReportMode } from "../../api/scanReports.ts";
import { fetchSubmission, type Submission } from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { FilterChips, type Chip } from "../../components/FilterChips.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { NotApplicableSection } from "../shared/NotApplicableSection.tsx";
import { reportTitle } from "../shared/scanReports.ts";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { BatchesSection } from "./BatchesSection.tsx";
import { auditHistoryPath, CM_INBOX_PATH } from "./paths.ts";
import { CHANGED_SINCE_LAST_REVIEW, NOT_IN_A_BATCH, ReviewFindingsList } from "./ReviewFindingsList.tsx";
import { ReviewForm } from "./ReviewForm.tsx";
import { FinishReview } from "./FinishReview.tsx";
import { ReviewSessionContext, useReviewSession, type ReviewSession } from "./reviewSession.ts";

/** The Scan Reports marked not applicable: each justification, clearly marked, with its review and Confirm and Dispute. */
function ReviewedJustifications({ reports }: { reports: readonly ScanReportMode[] }) {
  const { submissionId, reviewable, classification, reviewed } = useReviewSession();
  const reviewForm = (report: ScanReportMode) => (
    <ReviewForm
      idPrefix={`review-${report.reportType}`}
      subject={`the not-applicable justification of ${reportTitle(report.reportType)}`}
      classification={classification}
      send={(input) => reviewScanReport(submissionId, report.reportType, report.justificationVersion, input)}
      onReviewed={reviewed}
    />
  );
  return (
    <NotApplicableSection id="review-not-applicable" reports={reports} controls={reviewable ? reviewForm : undefined} />
  );
}

type FindingsView = "all" | "changed";

/** Every Finding, grouped by batch; or only those changed since their last review, to re-review after a return. */
const VIEWS: readonly Chip<FindingsView>[] = [
  { key: "all", label: "All Findings" },
  { key: "changed", label: "Changed since last review" },
];

/** The Findings whose proposal changed since your last review of them, batched or not, each to review again. */
function ChangedFindingsSection() {
  return (
    <section className="card" aria-labelledby="review-changed">
      <h3 id="review-changed">Findings changed since your last review</h3>
      <p className="muted">
        Proposed again since you last reviewed them. Everything you confirmed and that is not listed here stays
        confirmed; Findings never reviewed are under All Findings.
      </p>
      <ReviewFindingsList
        filter={CHANGED_SINCE_LAST_REVIEW}
        heading="Changed since last review"
        caption="Findings changed since your last review, the most severe first"
      />
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
 * segregation of duties (story 58), and the screen shows its problem. Everything a person typed is shown as text. After a
 * return, the Changed since last review filter narrows the Findings to those proposed again since (story 56).
 */
export function ReviewScreen({ submissionId, classification }: { submissionId: string; classification: string }) {
  const loadSubmission = useCallback(() => fetchSubmission(submissionId), [submissionId]);
  const loadReports = useCallback(() => fetchScanReportModes(submissionId), [submissionId]);
  const [refreshKey, setRefreshKey] = useState(0);
  const submission = useResource(loadSubmission, refreshKey);
  const reports = useResource(loadReports, refreshKey);
  const reviewable = submission.data?.state === "IN_REVIEW";
  const reviewed = useCallback(() => setRefreshKey((n) => n + 1), []);
  const [view, setView] = useState<FindingsView>("all");
  const session = useMemo<ReviewSession>(
    () => ({ submissionId, reviewable, classification, refreshKey, reviewed }),
    [submissionId, reviewable, classification, refreshKey, reviewed],
  );

  return (
    <ReviewSessionContext.Provider value={session}>
      <div className="card">
        <p>
          <AppLink path={CM_INBOX_PATH}>Back to your inbox</AppLink>
          {" · "}
          <AppLink path={auditHistoryPath(submissionId)}>Audit history</AppLink>
        </p>
        <h2>Review VA Submission</h2>
        {submission.error ? <ErrorPanel problem={submission.error.problem} /> : null}
        {submission.data ? <SubmissionSummary submission={submission.data} /> : null}
        {reports.error ? <ErrorPanel problem={reports.error.problem} /> : null}
        {reports.data ? <ReviewedJustifications reports={reports.data} /> : null}
        <FilterChips label="Show Findings" chips={VIEWS} selected={view} onSelect={setView} />
        {view === "changed" ? (
          <ChangedFindingsSection />
        ) : (
          <>
            <BatchesSection />
            <section className="card" aria-labelledby="review-findings">
              <h3 id="review-findings">Findings proposed one by one</h3>
              <ReviewFindingsList
                filter={NOT_IN_A_BATCH}
                heading="Findings not in a batch"
                caption="Findings to review one by one, the most severe first"
              />
            </section>
          </>
        )}
        {reviewable ? <FinishReview /> : null}
      </div>
    </ReviewSessionContext.Provider>
  );
}
