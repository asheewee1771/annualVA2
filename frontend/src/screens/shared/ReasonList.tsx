import type { Reason, ReasonFile } from "../../api/problem.ts";
import { SCAN_REPORTS } from "./scanReports.ts";

/** A file a reason names, by its name and its Scan Report, as text. */
function fileWords(file: ReasonFile): string {
  const report = SCAN_REPORTS.find((each) => each.type === file.reportType);
  return `${file.name} (${report ? report.title : file.reportType})`;
}

/** What stands in the way of a move, each reason in the server's words with the files it names. */
export function ReasonList({ reasons }: { reasons: readonly Reason[] }) {
  return (
    <ul>
      {reasons.map((reason, index) => (
        <li key={`${reason.reason}-${reason.reportType ?? ""}-${index}`}>
          {reason.message}
          {reason.files.length > 0 ? (
            <ul>
              {reason.files.map((file) => (
                <li key={file.fileId}>{fileWords(file)}</li>
              ))}
            </ul>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
