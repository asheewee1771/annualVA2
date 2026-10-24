import type { ReactNode } from "react";
import type { ScanReportMode } from "../../api/scanReports.ts";
import { REVIEW_LABELS } from "./findingStatuses.ts";
import { LatestReview } from "./LatestReview.tsx";
import { reportTitle } from "./scanReports.ts";

/** A Scan Report marked not applicable, clearly marked: its justification, review state and latest review, as text. */
function JustificationSummary({ report }: { report: ScanReportMode }) {
  const latest = report.latestReview;
  return (
    <>
      <p>
        <strong>{reportTitle(report.reportType)}</strong> <span className="flag flag--warning">Not applicable</span>
        {report.reviewState ? <span className="muted"> · {REVIEW_LABELS[report.reviewState]}</span> : null}
      </p>
      <p className="finding-detail">{report.justification}</p>
      {latest ? (
        <LatestReview review={latest} current={latest.justificationVersion === report.justificationVersion} />
      ) : null}
    </>
  );
}

/**
 * The Scan Reports of a VA Submission marked not applicable (story 52): each justification, clearly marked, with its
 * review, and whatever controls the reader has beneath it.
 *
 * @param id the heading's id, unique on the screen
 * @param controls what the reader can do about one justification; nothing for a read-only screen
 */
export function NotApplicableSection({
  id,
  reports,
  controls,
}: {
  id: string;
  reports: readonly ScanReportMode[];
  controls?: ((report: ScanReportMode) => ReactNode) | undefined;
}) {
  const notApplicable = reports.filter((report) => report.mode === "NOT_APPLICABLE");
  return (
    <section className="card" aria-labelledby={id}>
      <h3 id={id}>Scan Reports marked not applicable ({notApplicable.length})</h3>
      {notApplicable.length === 0 ? <p className="muted">Every Scan Report was provided.</p> : null}
      {notApplicable.map((report) => (
        <div key={report.reportType} className="review-item">
          <JustificationSummary report={report} />
          {controls?.(report)}
        </div>
      ))}
    </section>
  );
}
