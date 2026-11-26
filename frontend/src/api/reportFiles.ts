import { download, request } from "./client.ts";
import type { Download } from "./files.ts";

/** The three Scan Reports every VA Submission holds. */
export type ReportType = "DEPENDENCY_CHECK" | "SECURE_CONFIGURATION" | "BURP_SUITE";

/** What a file is for: a report read for Findings, or a PDF or HTML report attached for people to read. */
export type FileKind = "MACHINE_READABLE" | "EVIDENCE";

/**
 * Where a file is in the upload pipeline. The stages run in a fixed order (quarantine, malware scan, type check, schema
 * check, parse, then the age check, which rejects a scan dated after today; SCHEMA_OK is only a stage a file passes through
 * on its way to PARSED, as every format has a parser); a
 * supporting evidence file stops after the type check and rests in READY, never read; a REJECTED_ status says the file
 * stopped at one of them and why.
 */
export type PipelineStatus =
  | "QUARANTINED"
  | "SCAN_SUBMITTED"
  | "SCAN_CLEAN"
  | "TYPE_OK"
  | "SCHEMA_OK"
  | "PARSED"
  | "READY"
  | "REJECTED_MALWARE"
  | "REJECTED_SANITIZED"
  | "REJECTED_SCAN_TIMEOUT"
  | "REJECTED_TYPE"
  | "REJECTED_TOO_LARGE"
  | "REJECTED_TOO_MANY_FINDINGS"
  | "REJECTED_UNREADABLE"
  | "REJECTED_SCHEMA"
  | "REJECTED_HASH_MISMATCH"
  | "REJECTED_ENCRYPTED"
  | "REJECTED_FUTURE_DATED";

const MEGABYTE = 1024 * 1024;

/**
 * What the server accepts for each Scan Report, so a file that cannot be accepted is not sent at all. The server stays the
 * authority: it decides the type from the file's bytes and applies the exact limit of the format (Dependency-Check and
 * XCCDF 25 MB, ARF, Nessus and Burp 50 MB; ADR 0021). These are the largest limit and the usual extensions per report.
 */
export const REPORT_UPLOAD_LIMITS: Readonly<Record<ReportType, { maxBytes: number; extensions: readonly string[] }>> = {
  DEPENDENCY_CHECK: { maxBytes: 25 * MEGABYTE, extensions: [".xml", ".json"] },
  SECURE_CONFIGURATION: { maxBytes: 50 * MEGABYTE, extensions: [".xml", ".nessus"] },
  BURP_SUITE: { maxBytes: 50 * MEGABYTE, extensions: [".xml"] },
};

/** What the server accepts of one format of supporting evidence. */
export interface EvidenceLimit {
  /** What to call the format when telling the person. */
  label: string;
  maxBytes: number;
  extensions: readonly string[];
  /** The type a browser reports for such a file; it reports none for a file type the system does not know. */
  mimeType: string;
}

/**
 * What the server accepts as supporting evidence under any Scan Report: a PDF up to 20 MB or an HTML report up to 10 MB
 * (ADR 0026). Early feedback only: the server decides the type from the file's leading bytes, never from the name.
 */
export const EVIDENCE_UPLOAD_LIMITS: readonly EvidenceLimit[] = [
  { label: "PDF", maxBytes: 20 * MEGABYTE, extensions: [".pdf"], mimeType: "application/pdf" },
  { label: "HTML", maxBytes: 10 * MEGABYTE, extensions: [".html", ".htm"], mimeType: "text/html" },
];

/** A Scan Report file as the server reports it. Nothing in it comes from the file's content. */
export interface ReportFile {
  id: string;
  submissionId: string;
  reportType: ReportType;
  kind: FileKind;
  /** The name the person gave it, as text; never markup. */
  name: string;
  sizeBytes: number;
  /** The SHA-256 of the bytes as received, lower-case hex. */
  uploadSha256: string;
  status: PipelineStatus;
  /** The plain sentence for a rejected file; null for any other. */
  message: string | null;
  /** Scan attempts made so far, at most three. */
  scanAttempts: number;
  /** An instant (UTC). */
  uploadedAt: string;
  /** The parsed report file this one is a rescan of; null for an ordinary upload. */
  replacesId: string | null;
  /** The rescan that replaced this file once it was parsed; null while none has. A superseded file is kept. */
  supersededBy: string | null;
  /**
   * Whether a rescan that is still there (not deleted) supersedes this file (ticket 68). A file whose rescan was deleted
   * keeps `supersededBy` but is false here, and can then be deleted itself.
   */
  supersededByLiveRescan: boolean;
  /**
   * When an approved disposal deleted this superseded file's content once its VA Submission was Endorsed (ticket 49); the
   * record stays, and the content can no longer be downloaded. Null while the content is held.
   */
  contentDisposedAt: string | null;
}

const files = (submissionId: string): string => `/api/submissions/${encodeURIComponent(submissionId)}/files`;

/** GET /api/submissions/{id}/files: every file of the VA Submission with its pipeline status, oldest first. */
export function fetchReportFiles(submissionId: string): Promise<ReportFile[]> {
  return request<ReportFile[]>("GET", files(submissionId));
}

/**
 * POST /api/submissions/{id}/files: uploads one file of a Scan Report, a machine-readable report or supporting evidence
 * (the SMO/Tech Lead of the System). The server quarantines it and answers 202 at once; the scan and the checks run
 * afterwards, so the status is read again. Its type is decided from its bytes, never from the name.
 */
export function uploadReportFile(
  submissionId: string,
  reportType: ReportType,
  kind: FileKind,
  file: File,
): Promise<ReportFile> {
  const form = new FormData();
  form.append("reportType", reportType);
  form.append("kind", kind);
  form.append("file", file);
  return request<ReportFile>("POST", files(submissionId), form);
}

/**
 * POST .../files/{fileId}/replace: uploads a rescan of one parsed report file (the SMO/Tech Lead of the System). It is
 * quarantined and checked like any upload, and only once it is parsed does it take the old file's place: the Findings it
 * still reports keep their status, new ones are added and those it no longer reports are proposed Remediated. A rescan that
 * is rejected changes nothing.
 */
export function replaceReportFile(submissionId: string, fileId: string, file: File): Promise<ReportFile> {
  const form = new FormData();
  form.append("file", file);
  return request<ReportFile>("POST", `${files(submissionId)}/${encodeURIComponent(fileId)}/replace`, form);
}

/**
 * DELETE /api/submissions/{id}/files/{fileId}: deletes a report file uploaded by mistake (the SMO/Tech Lead of the System,
 * in Draft or Returned for Rework; ticket 60), answered with 204. Its places go; a Finding no other file reports is removed
 * if it never had a proposal, and otherwise is proposed Remediated for the Cyber Manager. A parsed rescan is deleted like
 * any file, and the file it replaced can then be deleted too (it stays marked replaced). Supporting evidence, and a file
 * replaced by a rescan that is still there, are refused.
 */
export function deleteReportFile(submissionId: string, fileId: string): Promise<void> {
  return request<void>("DELETE", `${files(submissionId)}/${encodeURIComponent(fileId)}`);
}

/** The longest a confirmed target may be: the server's limit. */
export const MAX_TARGET_LENGTH = 200;

/**
 * What a parsed report file says about the scan, and what the SMO/Tech Lead confirmed of it. Everything but the flags is
 * report content or what a person typed: text only.
 */
export interface ReportFileMetadata {
  fileId: string;
  /** The business date (yyyy-mm-dd, Singapore) of the scan the file names; null when it names none. */
  scanDay: string | null;
  /** True for a Burp Suite export, whose only date is when it was exported, not when the scan was made. */
  scanDateIsExportTime: boolean;
  /** The target the file names; null when it names none. */
  target: string | null;
  /** The scan date the SMO/Tech Lead gave (yyyy-mm-dd); null until they confirm it. */
  confirmedScanDate: string | null;
  confirmedTarget: string | null;
  /**
   * True when the confirmed scan date differs from the file's own day (for a Burp export, its export day): flagged for the
   * reviewer, never refused.
   */
  scanDateEdited: boolean;
  /** True when the confirmed target differs from the file's: flagged for the reviewer, never refused. */
  targetEdited: boolean;
}

const metadataOf = (submissionId: string, fileId: string): string =>
  `${files(submissionId)}/${encodeURIComponent(fileId)}/metadata`;

/** GET .../files/{fileId}/metadata: the scan date and target a parsed report file names, and what was confirmed. */
export function fetchReportFileMetadata(submissionId: string, fileId: string): Promise<ReportFileMetadata> {
  return request<ReportFileMetadata>("GET", metadataOf(submissionId, fileId));
}

/**
 * PUT .../files/{fileId}/metadata: the SMO/Tech Lead confirms or corrects the scan date (yyyy-mm-dd, not after today) and
 * the target. A scan date or target that differs from the file's is flagged, never refused; a field that is wrong is a 400 that names it.
 */
export function confirmReportFileMetadata(
  submissionId: string,
  fileId: string,
  confirmation: { scanDate: string; target: string },
): Promise<ReportFileMetadata> {
  return request<ReportFileMetadata>("PUT", metadataOf(submissionId, fileId), confirmation);
}

/**
 * GET .../files/{fileId}/content: the file as an attachment, only once it has passed every stage. It is handed to the
 * browser's save step and never shown in the page: an HTML evidence file is active content.
 */
export function fetchReportFileContent(submissionId: string, file: Pick<ReportFile, "id" | "name">): Promise<Download> {
  return download(`${files(submissionId)}/${encodeURIComponent(file.id)}/content`, file.name);
}
