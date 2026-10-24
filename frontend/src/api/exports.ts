import { download, request } from "./client.ts";
import type { Download } from "./files.ts";

export type ExportStatus = "QUEUED" | "READY" | "FAILED" | "EXPIRED";

/** An export as its requester reads it (ticket 44). */
export interface Export {
  id: string;
  type: "MGMT_SUMMARY";
  format: "XLSX";
  cycle: number;
  /** The one Quarter it covers, or null for the whole Cycle. */
  quarter: number | null;
  classification: "CONFIDENTIAL" | "RESTRICTED";
  /** How many Systems it lists. */
  systemCount: number;
  status: ExportStatus;
  requestedAt: string;
  readyAt: string | null;
  /** 24 hours after it was made; null until then. */
  expiresAt: string | null;
}

/** What a VA POC asks for: the whole Cycle, or one Quarter, and every System or some of them. */
export interface ExportRequest {
  type: "MGMT_SUMMARY";
  format: "XLSX";
  cycle?: number;
  quarter?: number;
  systems?: string[];
}

const EXPORTS = "/api/exports";

/** POST /api/exports, a step-up action: the export is queued and made in the background. */
export function requestExport(asked: ExportRequest): Promise<Export> {
  return request<Export>("POST", EXPORTS, asked);
}

/** The caller's exports of the last 48 hours, newest first. */
export function fetchExports(): Promise<Export[]> {
  return request<Export[]>("GET", EXPORTS);
}

/** The file of a ready export, which only its requester may download. */
export function downloadExport(id: string): Promise<Download> {
  return download(`${EXPORTS}/${encodeURIComponent(id)}/download`, "management-summary.xlsx");
}
