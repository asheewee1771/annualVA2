import { request } from "./client.ts";
import type { ReportType } from "./reportFiles.ts";
import type { Reason } from "./problem.ts";

/** How severe a Finding is, the most severe first. */
export type Severity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFORMATIONAL";

/** How sure Burp Suite says it is that an issue is real; only Burp rates its issues. */
export type Confidence = "CERTAIN" | "FIRM" | "TENTATIVE";

/** The disposition the SMO/Tech Lead proposes for a Finding. */
export type FindingStatus = "REMEDIATED" | "FALSE_POSITIVE" | "RISK_ACCEPTED" | "REMEDIATION_PLANNED";

/** Where the Cyber Manager's review of the current proposal is; a Finding with no proposal has none. */
export type ReviewState = "PENDING" | "CONFIRMED" | "DISPUTED";

/** The longest a long free text (evidence, justification, reason, plan) may be, and a name or role: the server's limits. */
export const MAX_TEXT_LENGTH = 4000;
export const MAX_NAME_LENGTH = 200;

/** One proposal of a Finding Status with the fields of its status (the rest are null). The texts are what a person typed. */
export interface Proposal {
  id: string;
  status: FindingStatus;
  evidence: string | null;
  justification: string | null;
  reason: string | null;
  approverName: string | null;
  approverRole: string | null;
  /** A supporting evidence file of the same VA Submission, downloaded through the file endpoint. */
  approvalMemoFileId: string | null;
  plan: string | null;
  /** A business date (YYYY-MM-DD, Asia/Singapore). */
  targetDate: string | null;
  /** An instant (UTC). */
  proposedAt: string;
  /** The Bulk-set batch it was proposed in; null for a proposal made for one Finding alone. */
  batchId: string | null;
  /**
   * Who made it: the SMO/Tech Lead, or a rescan that no longer reported the Finding anywhere (Remediated, with the rescan
   * named in the evidence, for the Cyber Manager to review).
   */
  proposedAs: "SMO" | "RESCAN";
  /**
   * For a rescan's proposal, the file the rescan replaced: where the Finding was last reported. It is kept and downloaded
   * like any file; null for every other proposal.
   */
  replacedFile: SourceFile | null;
}

/** An uploaded Scan Report file a Finding was read from. */
export interface SourceFile {
  id: string;
  name: string;
}

/** Where a Finding came from: read from a report file, or typed in by the SMO/Tech Lead (shown as Manually Entered). */
export type FindingSource = "PARSED" | "MANUAL";

/** A Finding as the list reads it. Every text in it was read from a report or typed by a person: it is rendered only as text. */
export interface Finding {
  id: string;
  reportType: ReportType;
  source: FindingSource;
  /** What a Finding entered by hand is, in words; null for a parsed one. */
  title: string | null;
  /** The CVE, GHSA or rule id as the report names it. */
  identifier: string;
  packageOrRule: string;
  severity: Severity;
  /** A check that did not complete at any place it was reported. */
  didNotComplete: boolean;
  confidence: Confidence | null;
  occurrenceCount: number;
  sourceFiles: SourceFile[];
  reviewState: ReviewState | null;
  currentProposal: Proposal | null;
  /**
   * The Cyber Manager's latest review of this Finding, of whichever proposal it was: it is about the current proposal only
   * when its `proposalId` is the current proposal's id. Null until the first review.
   */
  latestReview: Review | null;
}

/** What the Cyber Manager decides about a proposal or a not-applicable justification. */
export type ReviewDecision = "CONFIRMED" | "DISPUTED";

/** The longest a review comment may be: the server's limit. */
export const MAX_COMMENT_LENGTH = 4000;

/** One review by a Cyber Manager. The comment and the justification are what a person typed: text only. */
export interface Review {
  id: string;
  decision: ReviewDecision;
  /** Always present on a Dispute; null on a confirmation without one. */
  comment: string | null;
  /** For a Finding, the proposal reviewed; null for a not-applicable justification. */
  proposalId: string | null;
  /** For a not-applicable Scan Report, the justification as it was when reviewed; null for a Finding. */
  justification: string | null;
  /** For a not-applicable Scan Report, the version of the justification reviewed; null for a Finding. */
  justificationVersion: number | null;
  /** A user id, never a name or email. */
  reviewedBy: string;
  /** An instant (UTC). */
  reviewedAt: string;
}

/** What a review sends: the decision and a comment, which a Dispute requires. */
export interface ReviewInput {
  decision: ReviewDecision;
  comment?: string;
}

/**
 * POST /api/findings/{id}/reviews: the Cyber Manager confirms or Disputes the Finding's current proposal, naming the
 * proposal they read; the server refuses it when that is no longer the current one, and when segregation of duties does
 * not allow the caller to review this VA Submission.
 */
export function reviewFinding(findingId: string, proposalId: string, input: ReviewInput): Promise<Review> {
  return request<Review>("POST", `/api/findings/${encodeURIComponent(findingId)}/reviews`, { ...input, proposalId });
}

export interface FindingPage {
  items: Finding[];
  /** Zero-based. */
  page: number;
  size: number;
  /** How many Findings there are in all. */
  total: number;
}

/**
 * What a proposal sends: the status and the fields it requires and no others (the server refuses a field the status does not
 * use, and names a missing one).
 */
export interface ProposalInput {
  status: FindingStatus;
  evidence?: string;
  justification?: string;
  reason?: string;
  approverName?: string;
  approverRole?: string;
  approvalMemoFileId?: string;
  plan?: string;
  targetDate?: string;
}

/** What the list is narrowed to on the server; every part is optional and the parts given all apply. */
export interface FindingFilter {
  severity?: readonly Severity[];
  reviewState?: ReviewState;
  /** No proposal yet, or a Disputed one: what the SMO/Tech Lead still has to act on. */
  needsAttention?: boolean;
  /** Only the members whose current proposal was made in this Bulk-set batch. */
  batchId?: string;
  /** Only the Findings whose current proposal was made in a batch (true), or only the others (false). */
  batched?: boolean;
}

/** The most a page of the list may hold: the server's limit. */
const MAX_PAGE_SIZE = 500;

function findingsPath(submissionId: string, filter: FindingFilter, page: number, size: number): string {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filter.severity && filter.severity.length > 0) query.set("severity", filter.severity.join(","));
  if (filter.reviewState) query.set("reviewState", filter.reviewState);
  if (filter.needsAttention) query.set("needsAttention", "true");
  if (filter.batchId) query.set("batchId", filter.batchId);
  if (filter.batched !== undefined) query.set("batched", String(filter.batched));
  return `/api/submissions/${encodeURIComponent(submissionId)}/findings?${query.toString()}`;
}

/**
 * GET /api/submissions/{id}/findings: a page of the VA Submission's Findings that match the filter, the most severe first,
 * with each one's current proposal and review state. The total is the number that match.
 */
export function fetchFindings(
  submissionId: string,
  filter: FindingFilter,
  page: number,
  size: number,
): Promise<FindingPage> {
  return request<FindingPage>("GET", findingsPath(submissionId, filter, page, size));
}

/**
 * The ids of every Finding that matches the filter, read page by page, for "select all that match". It stops once it has
 * more than `limit`, since a batch can hold no more than that anyway.
 */
export async function fetchMatchingIds(submissionId: string, filter: FindingFilter, limit: number): Promise<string[]> {
  const ids: string[] = [];
  for (let page = 0; ; page++) {
    const result = await fetchFindings(submissionId, filter, page, MAX_PAGE_SIZE);
    ids.push(...result.items.map((finding) => finding.id));
    if (result.items.length < MAX_PAGE_SIZE || ids.length >= result.total || ids.length > limit) return ids;
  }
}

/**
 * POST /api/findings/{id}/proposals: the SMO/Tech Lead proposes a Finding Status with the field it requires. Every proposal
 * is kept; the Finding's current proposal changes and its review goes back to pending. A field that is wrong is a 400 that
 * names it.
 */
export function proposeStatus(findingId: string, input: ProposalInput): Promise<Proposal> {
  return request<Proposal>("POST", `/api/findings/${encodeURIComponent(findingId)}/proposals`, input);
}

/** The most Findings one Bulk-set batch may hold: the server's limit. */
export const MAX_BATCH_SIZE = 1000;

/** A Bulk-set batch as its step answers it. */
export interface Batch {
  batchId: string;
  count: number;
}

/**
 * POST /api/submissions/{id}/findings/bulk-proposals: one Finding Status with its fields for every Finding named, as one
 * batch. The list is refused as a whole when one of them is not a Finding of this VA Submission; a field that is wrong is a
 * 400 that names it, as for one Finding.
 */
export function proposeInBulk(
  submissionId: string,
  findingIds: readonly string[],
  input: ProposalInput,
): Promise<Batch> {
  return request<Batch>("POST", `/api/submissions/${encodeURIComponent(submissionId)}/findings/bulk-proposals`, {
    findingIds,
    ...input,
  });
}

/**
 * A Bulk-set batch as the review screen lists it: what was proposed and where its members stand. A member is still in the
 * batch while its current proposal is the batch's; one proposed again since is reviewed on its own.
 */
export interface BatchSummary {
  batchId: string;
  /** A user id, never a name or email. */
  proposedBy: string;
  /** An instant (UTC). */
  proposedAt: string;
  /** What every member was proposed (the first member's proposal; the status and fields are the same on each). */
  proposal: Proposal;
  /** How many Findings the batch was applied to. */
  count: number;
  /** Members still in the batch awaiting review: what Confirm batch or Dispute batch decides. */
  awaitingReview: number;
  confirmed: number;
  disputed: number;
  /** Members proposed again since, reviewed one by one. */
  changedSince: number;
}

/** GET /api/submissions/{id}/batches: the Bulk-set batches of the VA Submission, in the order they were made. */
export function fetchBatches(submissionId: string): Promise<BatchSummary[]> {
  return request<BatchSummary[]>("GET", `/api/submissions/${encodeURIComponent(submissionId)}/batches`);
}

/** Why a batch review left a member out: it was proposed again (or withdrawn by a rescan), or already reviewed. */
export type LeftOutReason = "PROPOSAL_CHANGED" | "REVIEWED";

/** One review of a Bulk-set batch: the Findings it decided and the members it left out, each with why. */
export interface BatchReview {
  id: string;
  batchId: string;
  decision: ReviewDecision;
  comment: string | null;
  reviewedBy: string;
  reviewedAt: string;
  reviewed: string[];
  leftOut: { findingId: string; reason: LeftOutReason }[];
}

/**
 * POST /api/batches/{id}/reviews: the Cyber Manager confirms or Disputes, with one comment, every member of the batch
 * still awaiting review; members changed since are left out and named in the answer.
 */
export function reviewBatch(batchId: string, input: ReviewInput): Promise<BatchReview> {
  return request<BatchReview>("POST", `/api/batches/${encodeURIComponent(batchId)}/reviews`, input);
}

/** A report file as the readiness check names it: the name the person gave it is text, never markup. */
export interface NamedFile {
  fileId: string;
  name: string;
  reportType: ReportType;
}

/**
 * How far the VA Submission is: what the progress bar shows (Carried Over and Target Date Missed are none in Release 1),
 * and the report files whose age stands in the way of submitting.
 */
export interface Readiness {
  total: number;
  /** With a proposed status, whatever its review. */
  proposed: number;
  /** With no proposed status yet: what is still to do. */
  unproposed: number;
  /** Awaiting the Cyber Manager's review. */
  awaitingReview: number;
  carriedOver: number;
  targetDateMissed: number;
  disputed: number;
  /** Report files more than 90 days old at today's date, in upload order. */
  tooOld: NamedFile[];
  /** Report files with no scan date to judge (none in the file, or only a Burp export time), until one is given. */
  undated: NamedFile[];
  /** Every reason it cannot be submitted now, apart from the Declaration ticked on the screen; empty when ready. */
  blockers: Reason[];
}

/** GET /api/submissions/{id}/readiness: the progress counts of the VA Submission's Findings. */
export function fetchReadiness(submissionId: string): Promise<Readiness> {
  return request<Readiness>("GET", `/api/submissions/${encodeURIComponent(submissionId)}/readiness`);
}

/** The longest each text of a Finding entered by hand may be: the server's limits. */
export const MANUAL_LIMITS = { title: 200, identifier: 200, packageOrRule: 500 } as const;

/** What a Finding entered by hand sends: its Scan Report (marked for Findings entered by hand) and what it is. */
export interface ManualFindingInput {
  reportType: ReportType;
  title: string;
  identifier: string;
  packageOrRule: string;
  severity: Severity;
}

/**
 * POST /api/submissions/{id}/findings/manual: the SMO/Tech Lead types in a Finding of a Scan Report its tool cannot export.
 * It is kept like any Finding and always shown as Manually Entered. A field that is wrong is a 400 that names it; the same
 * identifier and package or rule twice is refused.
 */
export function enterManualFinding(submissionId: string, input: ManualFindingInput): Promise<Finding> {
  return request<Finding>("POST", `/api/submissions/${encodeURIComponent(submissionId)}/findings/manual`, input);
}
