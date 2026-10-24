import { request } from "./client.ts";
import type { Review, ReviewInput, ReviewState } from "./findings.ts";
import type { ReportType } from "./reportFiles.ts";

/**
 * How a Scan Report is provided: its tool's report is uploaded (the default), it does not apply to the System (Dependency-
 * Check or Burp Suite only, with a justification the Cyber Manager reviews), or its Findings are entered by hand (with a
 * justification). Secure Configuration is always required.
 */
export type ReportMode = "UPLOAD" | "NOT_APPLICABLE" | "MANUAL";

/** The longest a justification may be: the server's limit. */
export const MAX_JUSTIFICATION_LENGTH = 4000;

/** One Scan Report of a VA Submission and how it is provided. The justification is what a person typed: text only. */
export interface ScanReportMode {
  reportType: ReportType;
  mode: ReportMode;
  /** Why it is not applicable or entered by hand; null when it is uploaded. */
  justification: string | null;
  /** The Cyber Manager's review of a not-applicable justification; null for any other mode. */
  reviewState: ReviewState | null;
  /**
   * The version of the justification as read now: it goes up whenever the justification or the mode changes. A review
   * names it, so a screen read before a rewrite cannot confirm the rewrite.
   */
  justificationVersion: number;
  /**
   * For a not-applicable Scan Report, the Cyber Manager's latest review of its justification with the wording it was about:
   * after a Dispute and a rewrite it still shows what was Disputed. Null for any other mode, and until the first review.
   */
  latestReview: Review | null;
}

/**
 * POST /api/submissions/{id}/reports/{type}/review: the Cyber Manager confirms or Disputes the justification of a Scan
 * Report marked not applicable, naming the version of it they read; the server refuses it when the justification has
 * changed since.
 */
export function reviewScanReport(
  submissionId: string,
  reportType: ReportType,
  justificationVersion: number,
  input: ReviewInput,
): Promise<Review> {
  return request<Review>("POST", `${reports(submissionId)}/${encodeURIComponent(reportType)}/review`, {
    ...input,
    justificationVersion: String(justificationVersion),
  });
}

const reports = (submissionId: string): string => `/api/submissions/${encodeURIComponent(submissionId)}/reports`;

/** GET /api/submissions/{id}/reports: the three Scan Reports, each with its mode. */
export function fetchScanReportModes(submissionId: string): Promise<ScanReportMode[]> {
  return request<ScanReportMode[]>("GET", reports(submissionId));
}

/**
 * PUT /api/submissions/{id}/reports/{type}/mode: sets how a Scan Report is provided (the SMO/Tech Lead of the System). A
 * justification goes with NOT_APPLICABLE and MANUAL and never with UPLOAD; the mode can change only while the Scan Report
 * holds no report file and no Finding.
 */
export function setScanReportMode(
  submissionId: string,
  reportType: ReportType,
  mode: ReportMode,
  justification: string | undefined,
): Promise<ScanReportMode> {
  return request<ScanReportMode>(
    "PUT",
    `${reports(submissionId)}/${encodeURIComponent(reportType)}/mode`,
    mode === "UPLOAD" ? { mode } : { mode, justification },
  );
}
