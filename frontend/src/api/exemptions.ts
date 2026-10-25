import { request } from "./client.ts";
import type { SubmissionState } from "./submissions.ts";

/** What an Exemption rests on: a VA done in the 3 months before the Quarter, or one scheduled in the 3 months after. */
export type ExemptionBasis = "PRIOR_VA" | "SCHEDULED_VA";

/** How each basis is named on screen, in the order a form offers them. */
export const BASIS_LABELS: Readonly<Record<ExemptionBasis, string>> = {
  PRIOR_VA: "A VA done within the 3 months before the Quarter",
  SCHEDULED_VA: "A VA scheduled within the 3 months after the Quarter (a conditional Exemption)",
};

/** The longest the evidence may be once the server has cleaned it (IM8 as-1). */
export const MAX_EVIDENCE_LENGTH = 4000;

/** An Exemption request as the server keeps it. The evidence is what a person typed: text only. */
interface Exemption {
  id: string;
  submissionId: string;
  basis: ExemptionBasis;
  /** A business date, YYYY-MM-DD. */
  vaDate: string;
  decision: "APPROVED" | "REJECTED" | null;
}

/** What the SMO/Tech Lead's request form sends; the server checks every field. */
export interface ExemptionRequest {
  basis: ExemptionBasis;
  vaDate: string;
  evidence: string;
  /** A READY supporting evidence file of the same VA Submission, when one is attached. */
  evidenceFileId?: string;
  /** The VA Cycle on screen; omitted for the one running today. */
  cycle?: number;
}

/**
 * POST /api/systems/{id}/exemptions: the SMO/Tech Lead requests an Exemption, which freezes the VA Submission (and creates
 * it for a Not Started System). The server answers 400 `invalid-field` naming a wrong field, 409 `files-scanning` while
 * a file is still being checked, and 409 `invalid-transition` once it is endorsed or a request is pending.
 */
export function requestExemption(systemId: string, body: ExemptionRequest): Promise<Exemption> {
  return request<Exemption>("POST", `/api/systems/${encodeURIComponent(systemId)}/exemptions`, body);
}

/**
 * POST /api/exemptions/{id}/approve or /reject: a VA POC decides. A step-up action: 403 `step-up-required` without a
 * sign-in from the last five minutes, 403 `sod-conflict` when the other VA POC must decide it, 409 `invalid-transition`
 * once it is decided.
 */
export function decideExemption(id: string, decision: "approve" | "reject"): Promise<Exemption> {
  return request<Exemption>("POST", `/api/exemptions/${encodeURIComponent(id)}/${decision}`, {});
}

/** One request waiting on a VA POC. The evidence is what the SMO/Tech Lead typed: text only. */
export interface QueuedExemption {
  exemptionId: string;
  submissionId: string;
  systemId: string;
  /** The System ID as the VA POC typed it in the template. */
  systemCode: string;
  systemName: string;
  quarter: number;
  basis: ExemptionBasis;
  vaDate: string;
  evidence: string;
  evidenceFileId: string | null;
  /** The state a rejection puts back. */
  previousState: SubmissionState;
  /** An instant (UTC). */
  requestedAt: string;
  /** Segregation of duties refuses this VA POC the decision: the other VA POC decides it. */
  sodConflict: boolean;
}

/** A recorded VA of a conditional Exemption waiting on a VA POC's confirmation. The evidence is text only. */
export interface QueuedConfirmation {
  exemptionId: string;
  submissionId: string;
  systemId: string;
  systemCode: string;
  systemName: string;
  /** The VA Cycle: a Q4 one is confirmed after its Cycle has closed. */
  cycle: number;
  quarter: number;
  /** The day the VA was scheduled for. */
  vaDate: string;
  /** The day the SMO/Tech Lead says it happened. */
  vaHappenedOn: string;
  vaEvidence: string;
  /** An instant (UTC). */
  vaRecordedAt: string;
  /** Segregation of duties refuses this VA POC the confirmation: the other VA POC confirms it. */
  sodConflict: boolean;
}

interface ExemptionQueue {
  cycle: number;
  requests: QueuedExemption[];
  /** The recorded VAs to confirm, of every VA Cycle, the earliest recorded first. */
  confirmations: QueuedConfirmation[];
}

/** What the SMO/Tech Lead's VA happened form sends; the server checks every field. */
export interface VaHappened {
  /** The day the scheduled VA happened, YYYY-MM-DD: after the Quarter, by the lapse date, not after today. */
  vaDate: string;
  /** What shows it happened: the report or its reference. */
  evidence: string;
}

/**
 * POST /api/exemptions/{id}/va-happened: the SMO/Tech Lead records that a conditional Exemption's scheduled VA happened;
 * from then on it does not lapse. The server answers 400 `invalid-field` naming a wrong field and 409
 * `invalid-transition` when it is not conditional, is recorded already or has lapsed.
 */
export function recordVaHappened(id: string, body: VaHappened): Promise<Exemption> {
  return request<Exemption>("POST", `/api/exemptions/${encodeURIComponent(id)}/va-happened`, body);
}

/**
 * POST /api/exemptions/{id}/confirm-va: a VA POC confirms the recorded VA, and the Exemption is no longer conditional. A
 * step-up action: 403 `step-up-required` without a sign-in from the last five minutes, 403 `sod-conflict` when the other
 * VA POC must confirm it, 409 `invalid-transition` once it is confirmed.
 */
export function confirmVa(id: string): Promise<Exemption> {
  return request<Exemption>("POST", `/api/exemptions/${encodeURIComponent(id)}/confirm-va`, {});
}

/** GET /api/dashboards/exemption-queue: the pending requests of a VA Cycle, oldest first. Every read is audited. */
export function fetchExemptionQueue(cycle?: number): Promise<ExemptionQueue> {
  return request<ExemptionQueue>(
    "GET",
    `/api/dashboards/exemption-queue${cycle === undefined ? "" : `?cycle=${String(cycle)}`}`,
  );
}
