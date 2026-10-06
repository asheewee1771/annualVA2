import { download, request } from "./client.ts";
import type { Download } from "./files.ts";

/** The fixed Excel template for the System Register (GET /api/system-register/template, VA POC only). */
export function fetchRegisterTemplate(): Promise<Download> {
  return download("/api/system-register/template", "system-register-template.xlsx");
}

/**
 * What an upload does with one row or one System: exactly one outcome per row. BLOCKED means the row breaks a rule
 * (its reasons say which); a System is never deleted.
 */
export type RegisterOutcome = "ADD" | "UPDATE" | "RETIRE" | "NO_CHANGE" | "BLOCKED";

/** Why a row is Blocked: a fixed code from the server, never text from the file. */
export type BlockReason =
  | "SYSTEM_ID_MISSING"
  | "SYSTEM_ID_INVALID"
  | "SYSTEM_ID_DUPLICATE"
  | "SYSTEM_ID_EXAMPLE"
  | "SYSTEM_NAME_MISSING"
  | "SYSTEM_NAME_INVALID"
  | "SMO_EMAIL_MISSING"
  | "SMO_EMAIL_INVALID"
  | "CYBER_MANAGER_MISSING"
  | "CYBER_MANAGER_EMAIL_INVALID"
  | "CYBER_MANAGER_IS_SMO"
  | "QUARTER_MISSING"
  | "QUARTER_INVALID";

/** A System as the Register has it, or as the file would make it (what was typed, for a Blocked row). */
export interface SystemSnapshot {
  name: string;
  smoEmail: string;
  cyberManagerEmail: string;
  quarter: string;
  retired: boolean;
}

export interface PreviewRow {
  /** The row in the spreadsheet; null for a System the file leaves out. */
  sheetRow: number | null;
  /** The System ID as typed; null when the cell was empty. */
  systemId: string | null;
  outcome: RegisterOutcome;
  /** Why the row is BLOCKED; empty for every other outcome. */
  reasons: BlockReason[];
  /** The SMO/Tech Lead's email matches no sign-in yet. */
  unlinkedSmo: boolean;
  before: SystemSnapshot | null;
  after: SystemSnapshot | null;
}

export interface PreviewCounts {
  add: number;
  update: number;
  retire: number;
  noChange: number;
  blocked: number;
  unlinkedSmo: number;
}

/** A Register change preview (POST /api/system-register/uploads): nothing in the Register has changed. */
export interface RegisterPreview {
  id: string;
  status: string;
  createdAt: string;
  /** An hour after the upload. */
  expiresAt: string;
  /** False when any row is Blocked. */
  canApply: boolean;
  counts: PreviewCounts;
  rows: PreviewRow[];
}

/** Uploads a filled-in template (VA POC only); the server decides the file's type from its bytes. */
export function uploadRegister(file: File): Promise<RegisterPreview> {
  const form = new FormData();
  form.append("file", file);
  return request<RegisterPreview>("POST", "/api/system-register/uploads", form);
}

/** A preview read back (GET /api/system-register/previews/{id}), until it expires an hour after the upload. */
export function fetchRegisterPreview(id: string): Promise<RegisterPreview> {
  return request<RegisterPreview>("GET", `/api/system-register/previews/${encodeURIComponent(id)}`);
}

/** What an apply did (POST /api/system-register/previews/{id}/apply). */
export interface AppliedRegister {
  previewId: string;
  status: string;
  appliedAt: string;
  added: number;
  updated: number;
  retired: number;
  unchanged: number;
  rolesGranted: number;
  rolesRevoked: number;
}

/**
 * Applies a confirmed preview (VA POC only). It is the step-up action for the Register, so the server refuses it with
 * `step-up-required` unless the person signed in again within five minutes; the app then offers the sign-in. The body
 * names the preview only so that it can be reopened when the person comes back from that sign-in (a file cannot be
 * kept, a preview id can); the server reads the preview from the address.
 */
export function applyRegisterPreview(id: string): Promise<AppliedRegister> {
  return request<AppliedRegister>("POST", `/api/system-register/previews/${encodeURIComponent(id)}/apply`, {
    previewId: id,
  });
}

/** A System in the Register table (GET /api/systems). */
export interface RegisterSystem {
  id: string;
  /** The System ID as the VA POC typed it in the template. */
  systemId: string;
  name: string;
  smoEmail: string;
  cyberManagerEmail: string;
  quarter: string;
  retired: boolean;
  /** Only a VA POC is told; null for anyone else. */
  unlinkedSmo: boolean | null;
}

/** The Systems the signed-in person may see: all of them for a VA POC, their own for an SMO or Cyber Manager. */
export function fetchSystems(): Promise<RegisterSystem[]> {
  return request<RegisterSystem[]>("GET", "/api/systems");
}
