import { download, request } from "./client.ts";
import type { SystemState } from "./dashboards.ts";
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
  | "QUARTER_INVALID"
  | "FILES_SCANNING"
  | "MOVE_IN_FLIGHT"
  | "TRANSFER_IN_FLIGHT"
  | "SEGREGATION_OF_DUTIES";

/**
 * What a row does to in-flight work: a VA Submission of the System that is not in a final state. In Release 1 only a
 * withdrawal is applied, once each one is confirmed; a move and a transfer are shown and blocked.
 */
export type RegisterEffect = "WILL_WITHDRAW" | "WILL_MOVE" | "WILL_TRANSFER";

/** An in-flight VA Submission a row's effects are on. */
export interface InFlightSubmission {
  submissionId: string;
  /** The VA Cycle's year. */
  cycle: number;
  /** Its state when the preview was made. */
  state: SystemState;
}

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
  /** What the row does to in-flight work; empty when it touches none. */
  effects: RegisterEffect[];
  /** The in-flight VA Submissions those effects are on. */
  inFlight: InFlightSubmission[];
}

export interface PreviewCounts {
  add: number;
  update: number;
  retire: number;
  noChange: number;
  blocked: number;
  unlinkedSmo: number;
  willWithdraw: number;
  willMove: number;
  willTransfer: number;
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
  /** VA Submissions made Withdrawn because their System was retired. */
  withdrawn: number;
}

/** What an apply sends, kept across a step-up sign-in so the preview and the confirmations come back. */
export interface ApplyRequest {
  previewId: string;
  /** The VA Submissions the VA POC confirmed withdrawing, one by one: exactly those under Will withdraw. */
  withdraw: string[];
}

/**
 * Applies a confirmed preview (VA POC only). It is the step-up action for the Register, so the server refuses it with
 * `step-up-required` unless the person signed in again within five minutes; the app then offers the sign-in. The body
 * names the preview so that it can be reopened when the person comes back from that sign-in (a file cannot be kept, a
 * preview id can), and the withdrawals confirmed; the server reads the preview from the address and refuses with
 * `withdrawal-unconfirmed` unless every withdrawal, and only those, is confirmed.
 */
export function applyRegisterPreview(id: string, withdraw: string[]): Promise<AppliedRegister> {
  const body: ApplyRequest = { previewId: id, withdraw };
  return request<AppliedRegister>("POST", `/api/system-register/previews/${encodeURIComponent(id)}/apply`, body);
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
