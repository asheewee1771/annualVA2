import { request } from "./client.ts";
import type { Page } from "./page.ts";

/**
 * The audit log as the server returns it (GET /api/audit-entries). An entry holds ids and fixed codes only: a Finding is
 * an id and no vulnerability detail ever comes with it.
 */
export interface AuditEntry {
  id: string;
  occurredAt: string;
  actorId: string | null;
  /** Roles held at the time, highest privilege first, comma-separated. */
  actorRole: string | null;
  action: string;
  outcome: string;
  systemId: string | null;
  submissionId: string | null;
  findingId: string | null;
  correlationId: string;
  detail: Record<string, string | number | boolean>;
}

/** What the log is narrowed by; an empty string does not narrow. Dates are business dates (Asia/Singapore). */
export interface AuditFilters {
  actor: string;
  action: string;
  system: string;
  submission: string;
  from: string;
  to: string;
}

export const NO_FILTERS: AuditFilters = { actor: "", action: "", system: "", submission: "", from: "", to: "" };

export const PAGE_SIZE = 50;

/** The actions the log can hold, in the words the screen shows. An action this build does not know shows as sent. */
const ACTION_LABELS: Readonly<Record<string, string>> = {
  SIGN_IN: "Sign-in",
  SIGN_OUT: "Sign-out",
  SIGN_IN_FAILED: "Sign-in refused",
  STEP_UP_STARTED: "Step-up started",
  STEP_UP_FULL_SIGN_IN: "Step-up by full sign-in",
  STEP_UP_OK: "Step-up completed",
  STEP_UP_FAILED: "Step-up refused",
  REQUEST_REFUSED: "Request refused",
  ROLE_GRANTED: "Role granted",
  ROLE_REVOKED: "Role revoked",
  ROLE_LAPSED: "Role lapsed",
  ROLE_RETURNED: "Role returned",
  REGISTER_TEMPLATE_DOWNLOADED: "System Register template downloaded",
  REGISTER_PREVIEWED: "System Register previewed",
  REGISTER_PREVIEW_VIEWED: "System Register preview viewed",
  REGISTER_UPLOAD_REJECTED: "System Register upload rejected",
  REGISTER_APPLIED: "System Register applied",
  REGISTER_SYSTEM_RETIRED: "System retired",
  SUBMISSION_STARTED: "VA Submission started",
  SUBMISSION_VIEWED: "VA Submission viewed",
  SUBMISSION_SUBMITTED: "VA Submission submitted",
  DECLARATION_PUBLISHED: "Declaration version published",
  SUBMISSION_OVERDUE: "Overdue",
  FILE_UPLOADED: "File uploaded",
  FILE_REJECTED: "File rejected",
  FILE_REPLACED: "File replaced",
  FILE_DELETED: "File deleted",
  FILE_VIEWED: "File viewed",
  FILE_DOWNLOADED: "File downloaded",
  FILE_METADATA_VIEWED: "File details viewed",
  FILE_METADATA_CONFIRMED: "Scan date confirmed",
  FINDINGS_VIEWED: "Findings viewed",
  STATUS_PROPOSED: "Finding Status proposed",
  PROPOSAL_WITHDRAWN: "Proposal withdrawn",
  FINDING_PROPOSALS_VIEWED: "Finding Status history viewed",
  BATCH_PROPOSED: "Bulk-set batch proposed",
  BATCHES_VIEWED: "Bulk-set batches viewed",
  READINESS_VIEWED: "Readiness viewed",
  REPORT_MODE_SET: "Scan Report mode set",
  SCAN_REPORTS_VIEWED: "Scan Reports viewed",
  FINDING_ENTERED: "Finding entered by hand",
  FINDING_DELETED: "Finding deleted",
  FINDING_REVIEWED: "Finding reviewed",
  REPORT_REVIEWED: "Not-applicable justification reviewed",
  BATCH_REVIEWED: "Bulk-set batch reviewed",
  REWORK_QUEUE_VIEWED: "Rework queue viewed",
  SUBMISSION_RETURNED: "Returned for Rework",
  SUBMISSION_PASSED: "Passed to endorsement",
  SUBMISSION_RETURNS_VIEWED: "Returns viewed",
  SUBMISSION_ENDORSED: "Endorsed",
  SUBMISSION_NOT_ENDORSED: "Not Endorsed",
  SUBMISSION_WITHDRAWN: "Withdrawn",
  SMO_UNREACHABLE: "SMO Unreachable",
  SMO_UNREACHABLE_CLEARED: "SMO Unreachable cleared",
  CYCLE_OPENED: "VA Cycle opened",
  CYCLE_CLOSED: "VA Cycle closed",
  EXEMPTION_REQUESTED: "Exemption requested",
  EXEMPTION_APPROVED: "Exemption approved",
  EXEMPTION_REJECTED: "Exemption rejected",
  EXEMPTIONS_VIEWED: "Exemption requests viewed",
  EXEMPTION_VA_RECORDED: "Conditional Exemption VA recorded",
  EXEMPTION_VA_CONFIRMED: "Conditional Exemption VA confirmed",
  EXEMPTION_LAPSED: "Conditional Exemption lapsed",
  ROLE_SUSPENDED: "Role suspended",
  ROLE_RESTORED: "Role restored",
  ROLE_HOLDERS_REVIEWED: "Role holders reviewed",
  EXPORT_REQUESTED: "Export requested",
  EXPORT_DOWNLOADED: "Export downloaded",
  DISPOSAL_LISTED: "Disposal list made",
  DISPOSAL_APPROVED: "Disposal list approved",
  DISPOSAL_EXECUTED: "Disposal carried out",
  AUDIT_LOG_VIEWED: "Audit log viewed",
};

export const AUDIT_ACTIONS: readonly string[] = Object.keys(ACTION_LABELS);

export function actionLabel(action: string): string {
  return Object.hasOwn(ACTION_LABELS, action) ? (ACTION_LABELS[action] ?? action) : action;
}

export function fetchAuditEntries(filters: AuditFilters, page: number): Promise<Page<AuditEntry>> {
  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  for (const [name, value] of Object.entries(filters)) {
    if (value !== "") query.set(name, value);
  }
  return request<Page<AuditEntry>>("GET", `/api/audit-entries?${query.toString()}`);
}
