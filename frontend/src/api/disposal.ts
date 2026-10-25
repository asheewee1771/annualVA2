import { request } from "./client.ts";

export type DisposalListStatus = "DRAFT" | "SUPERSEDED" | "APPROVED" | "EXECUTED";

export type DisposalItemKind =
  "SUBMISSION" | "FILE" | "SUPERSEDED_FILE" | "EXPORT" | "SYSTEM" | "CYCLE" | "AUDIT_RANGE";

export type DisposalReason =
  "CYCLE_RETENTION_ENDED" | "SUPERSEDED_AFTER_ENDORSEMENT" | "EXPORT_EXPIRED" | "SYSTEM_RETIRED";

/** One record, or group of records, a disposal list disposes of (ticket 49). Ids and codes only. */
export interface DisposalItem {
  kind: DisposalItemKind;
  /** The VA Submission, file, export or System; null for a Cycle or the audit range. */
  targetId: string | null;
  systemId: string | null;
  /** The System ID and name as the Register holds them now; null once the System is gone. */
  systemCode: string | null;
  systemName: string | null;
  cycle: number | null;
  reason: DisposalReason;
  /** For the audit range: how many entries were before the cutoff when it was listed. */
  entries: number | null;
}

/** A disposal list the retention job wrote. */
export interface DisposalList {
  id: string;
  status: DisposalListStatus;
  /** The business date it was computed on. */
  computedOn: string;
  createdAt: string;
  /** What was written before this instant belongs to the Cycles it disposes of; null when it disposes of none. */
  cutoff: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  executedAt: string | null;
  items: DisposalItem[];
}

const LISTS = "/api/disposal-lists";

/** GET /api/disposal-lists: the newest lists, newest first (VA POC only). */
export function fetchDisposalLists(): Promise<DisposalList[]> {
  return request<DisposalList[]>("GET", LISTS);
}

/** POST /api/disposal-lists/{id}/approve, a step-up action: the executor then disposes of the list. */
export function approveDisposalList(id: string): Promise<DisposalList> {
  return request<DisposalList>("POST", `${LISTS}/${encodeURIComponent(id)}/approve`, {});
}
