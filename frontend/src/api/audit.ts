import { request } from "./client.ts";

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
  detail: Record<string, string | number>;
}

export interface AuditPage {
  items: AuditEntry[];
  page: number;
  size: number;
  total: number;
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
  REGISTER_TEMPLATE_DOWNLOADED: "System Register template downloaded",
};

export const AUDIT_ACTIONS: readonly string[] = Object.keys(ACTION_LABELS);

export function actionLabel(action: string): string {
  return Object.hasOwn(ACTION_LABELS, action) ? (ACTION_LABELS[action] ?? action) : action;
}

export function fetchAuditEntries(filters: AuditFilters, page: number): Promise<AuditPage> {
  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  for (const [name, value] of Object.entries(filters)) {
    if (value !== "") query.set(name, value);
  }
  return request<AuditPage>("GET", `/api/audit-entries?${query.toString()}`);
}
