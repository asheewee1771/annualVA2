import type { ReportFile, ReportType } from "../../api/reportFiles.ts";

export interface ScanReport {
  type: ReportType;
  title: string;
  note: string;
  /** What to try when a file of this report is too large for the server; absent when there is nothing better to offer. */
  tooLargeHint?: string;
}

/** The three Scan Reports every VA Submission holds (CONTEXT.md); Secure Configuration is always required. */
export const SCAN_REPORTS: readonly ScanReport[] = [
  {
    type: "DEPENDENCY_CHECK",
    title: "OWASP Dependency-Check",
    note: "Required, unless you mark it not applicable with a justification the Cyber Manager confirms.",
  },
  {
    type: "SECURE_CONFIGURATION",
    title: "Secure Configuration",
    note: "Always required: a SCAP ARF or XCCDF result, or a Nessus export.",
    tooLargeHint:
      "An ARF or Nessus file can be at most 50 MB and an XCCDF results file 25 MB. If your ARF is too large, export the XCCDF results only; if your Nessus file is too large, export it in parts, with fewer hosts each.",
  },
  {
    type: "BURP_SUITE",
    title: "Burp Suite",
    note: "Required, unless you mark it not applicable with a justification the Cyber Manager confirms.",
    tooLargeHint:
      "A Burp Suite XML export can be at most 50 MB. If yours is too large, export the issues again without the request and response bodies, which are most of the file.",
  },
];

/** A file the pipeline has not finished with: the screen asks again until none is. */
export function inProgress(file: ReportFile): boolean {
  return (
    file.status === "QUARANTINED" ||
    file.status === "SCAN_SUBMITTED" ||
    file.status === "SCAN_CLEAN" ||
    file.status === "TYPE_OK"
  );
}
