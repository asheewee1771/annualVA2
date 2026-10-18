import type { ReportFile } from "../../api/reportFiles.ts";
import type { ScanReportMode } from "../../api/scanReports.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { usePolling } from "../../hooks/usePolling.ts";
import type { Resource } from "../../hooks/useResource.ts";
import { ScanReportCard } from "./ScanReportCard.tsx";
import { inProgress, SCAN_REPORTS } from "./scanReports.ts";

/**
 * The three Scan Report cards of a VA Submission with how each is provided and their files. A file moves through the
 * malware scan and the checks on the server, so the list is asked for again until none is still on its way (and not while
 * the tab is hidden). The list is the screen's, shared with the Findings, whose approval memos are among its files; so is
 * the word that a Finding was entered by hand, after which the Findings are asked for again.
 */
export function ScanReportsSection({
  submissionId,
  files,
  modes,
  classification,
  onFindingEntered,
}: {
  submissionId: string;
  files: Resource<ReportFile[]>;
  modes: Resource<ScanReportMode[]>;
  classification: string;
  onFindingEntered: () => void;
}) {
  // Only this submission's files: a screen that was showing another submission never shows its files for a moment.
  const ofThisSubmission = (files.data ?? []).filter((file) => file.submissionId === submissionId);
  const waiting = ofThisSubmission.some(inProgress);
  const { slow } = usePolling(waiting, files.loading, files.reload);

  return (
    <>
      {files.error ? <ErrorPanel problem={files.error.problem} /> : null}
      {modes.error ? <ErrorPanel problem={modes.error.problem} /> : null}
      {waiting && slow ? (
        <p className="notice" role="status">
          The checks are taking longer than usual. You can leave this page and come back; the files keep being checked.
        </p>
      ) : null}
      {SCAN_REPORTS.map((report) => (
        <ScanReportCard
          key={report.type}
          submissionId={submissionId}
          report={report}
          mode={modes.data?.find((each) => each.reportType === report.type)}
          files={ofThisSubmission.filter((file) => file.reportType === report.type)}
          classification={classification}
          onChanged={files.reload}
          onModeChanged={modes.reload}
          onFindingEntered={onFindingEntered}
        />
      ))}
    </>
  );
}
