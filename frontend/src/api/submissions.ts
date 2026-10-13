import { request } from "./client.ts";

/** Where a VA Submission is in its life; the other states arrive with the commands that enter them. */
export type SubmissionState = "DRAFT";

/** The steps at which a person acts on a VA Submission. */
export type SubmissionStep = "SUBMIT";

/** One person's first action at a step (segregation of duties). The person is an id, never a name or email. */
export interface Acted {
  step: SubmissionStep;
  userId: string;
  /** An instant (UTC). */
  at: string;
}

/** A VA Submission. Dates are business dates (YYYY-MM-DD, Asia/Singapore). */
export interface Submission {
  id: string;
  /** The System's own id (not its System ID). */
  systemId: string;
  cycle: number;
  quarter: number;
  state: SubmissionState;
  overdue: boolean;
  endorsedLate: boolean;
  submitDue: string;
  reviewDue: string;
  endorseDue: string;
  startedAt: string;
  acted: Acted[];
}

/**
 * POST /api/submissions: the SMO/Tech Lead starts the VA Submission for one of their Systems. The server answers 409
 * `invalid-transition` when it was started already, so a double click never makes a second one.
 */
export function startSubmission(systemId: string, cycle?: number): Promise<Submission> {
  return request<Submission>("POST", "/api/submissions", cycle === undefined ? { systemId } : { systemId, cycle });
}

/** GET /api/submissions/{id}: for anyone who may see the System. */
export function fetchSubmission(id: string): Promise<Submission> {
  return request<Submission>("GET", `/api/submissions/${encodeURIComponent(id)}`);
}
