import type { ReportFile, ReportType } from "../../api/reportFiles.ts";

export interface ScanReport {
  type: ReportType;
  title: string;
  note: string;
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
  },
  {
    type: "BURP_SUITE",
    title: "Burp Suite",
    note: "Required, unless you mark it not applicable with a justification the Cyber Manager confirms.",
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
