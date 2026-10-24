import { request } from "./client.ts";
import type { ReportType } from "./reportFiles.ts";

/**
 * Where a VA Submission is in its life. A System with none is Not Started; so is one an Exemption request created once
 * the request is rejected, which Start then takes.
 */
export type SubmissionState =
  | "NOT_STARTED"
  | "DRAFT"
  | "IN_REVIEW"
  | "RETURNED_FOR_REWORK"
  | "AWAITING_ENDORSEMENT"
  | "ENDORSED"
  | "EXEMPTION_REQUESTED"
  | "EXEMPTED"
  | "WITHDRAWN"
  | "NOT_ENDORSED";

/** Whether the SMO/Tech Lead is still preparing it: anything else is read-only (the server refuses changes). */
export function isEditable(state: SubmissionState): boolean {
  return state === "DRAFT" || state === "RETURNED_FOR_REWORK";
}

/** The steps at which a person acts on a VA Submission. */
export type SubmissionStep = "SUBMIT" | "REVIEW" | "ENDORSE";

/** One person's first action at a step (segregation of duties). The person is an id, never a name or email. */
export interface Acted {
  step: SubmissionStep;
  userId: string;
  /** An instant (UTC). */
  at: string;
}

/** The Declaration as accepted at the latest submit: the exact wording of its version, who accepted it and when. */
export interface AcceptedDeclaration {
  version: number;
  text: string;
  /** A user id, never a name or email. */
  acceptedBy: string;
  /** An instant (UTC). */
  acceptedAt: string;
}

/** A VA Submission. Dates are business dates (YYYY-MM-DD, Asia/Singapore). */
export interface Submission {
  id: string;
  /** The System's own id (not its System ID). */
  systemId: string;
  cycle: number;
  quarter: number;
  state: SubmissionState;
  /** Its current window passed without the next step; nothing locks. */
  overdue: boolean;
  /** When the Overdue flag was set (an instant, UTC); null while it is clear. */
  overdueSince: string | null;
  endorsedLate: boolean;
  submitDue: string;
  reviewDue: string;
  endorseDue: string;
  /** When it was started (an instant, UTC); null for a System its VA Cycle's close found Not Started. */
  startedAt: string | null;
  /** When it was last submitted for review (an instant, UTC); null until it is. */
  submittedAt: string | null;
  /** The Declaration accepted at its latest submit; null until it is submitted. */
  declaration: AcceptedDeclaration | null;
  acted: Acted[];
}

/** The fixed Declaration wording the SMO/Tech Lead accepts when submitting. */
export interface DeclarationText {
  version: number;
  text: string;
  /** True while the wording is not the agreed one: the screen marks it. */
  placeholder: boolean;
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

/** GET /api/declaration-text: the wording to accept now. */
export function fetchDeclarationText(): Promise<DeclarationText> {
  return request<DeclarationText>("GET", "/api/declaration-text");
}

/**
 * POST /api/submissions/{id}/return: the Cyber Manager returns it to the SMO/Tech Lead for rework with what is Disputed
 * attached, and an optional comment (blank sends none). The server answers 409 `nothing-disputed` when nothing is
 * Disputed, and 409 `invalid-transition` once it is not In Review.
 */
export function returnForRework(id: string, comment: string): Promise<Submission> {
  return request<Submission>(
    "POST",
    `/api/submissions/${encodeURIComponent(id)}/return`,
    comment.trim() === "" ? {} : { comment },
  );
}

/**
 * POST /api/submissions/{id}/pass-to-endorsement: the Cyber Manager passes it to the VA POCs. The server answers 409
 * `not-confirmed` with what is still unconfirmed (`reasons`), and 409 `invalid-transition` once it is not In Review.
 */
export function passToEndorsement(id: string): Promise<Submission> {
  return request<Submission>("POST", `/api/submissions/${encodeURIComponent(id)}/pass-to-endorsement`, {});
}

/**
 * POST /api/submissions/{id}/endorse: a VA POC endorses it (Endorsed Late after the Quarter's last day). A step-up action:
 * the server answers 403 `step-up-required` without a sign-in from the last five minutes, 403 `sod-conflict` when this
 * VA POC acted at, or is assigned to, another step of it, and 409 `invalid-transition` once it is not Awaiting
 * Endorsement.
 */
export function endorseSubmission(id: string): Promise<Submission> {
  return request<Submission>("POST", `/api/submissions/${encodeURIComponent(id)}/endorse`, {});
}

/**
 * A return for rework as kept: who returned it and when, what they wrote, and what was Disputed then, each item named by
 * the review that Disputed it. The comment is what a person typed: text only.
 */
export interface SubmissionReturn {
  id: string;
  step: SubmissionStep;
  /** Null when none was given: each Dispute carries its own comment. */
  comment: string | null;
  /** A user id, never a name or email. */
  returnedBy: string;
  /** An instant (UTC). */
  returnedAt: string;
  disputedFindings: { findingId: string; reviewId: string }[];
  disputedReports: { reportType: ReportType; reviewId: string }[];
}

/** GET /api/submissions/{id}/returns: the returns of the VA Submission, the latest first. */
export function fetchReturns(id: string): Promise<SubmissionReturn[]> {
  return request<SubmissionReturn[]>("GET", `/api/submissions/${encodeURIComponent(id)}/returns`);
}

/**
 * POST /api/submissions/{id}/submit: submits for review with the Declaration of this version ticked. The server answers
 * 409 `not-ready` with every reason it cannot be submitted yet (`reasons`), and 409 `invalid-transition` once it is not
 * in Draft or Returned for Rework.
 */
export function submitSubmission(id: string, declarationVersion: number): Promise<Submission> {
  return request<Submission>("POST", `/api/submissions/${encodeURIComponent(id)}/submit`, {
    declarationAccepted: true,
    declarationVersion,
  });
}
