import { request } from "./client.ts";
import type { ReportType } from "./reportFiles.ts";

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

/** A Finding as the list reads it. Every text in it was read from a report: it is rendered only as text. */
export interface Finding {
  id: string;
  reportType: ReportType;
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
}

/** The most a page of the list may hold: the server's limit. */
const MAX_PAGE_SIZE = 500;

function findingsPath(submissionId: string, filter: FindingFilter, page: number, size: number): string {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filter.severity && filter.severity.length > 0) query.set("severity", filter.severity.join(","));
  if (filter.reviewState) query.set("reviewState", filter.reviewState);
  if (filter.needsAttention) query.set("needsAttention", "true");
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

/** How far the Findings are: what the progress bar shows. Carried Over and Target Date Missed are none in Release 1. */
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
}

/** GET /api/submissions/{id}/readiness: the progress counts of the VA Submission's Findings. */
export function fetchReadiness(submissionId: string): Promise<Readiness> {
  return request<Readiness>("GET", `/api/submissions/${encodeURIComponent(submissionId)}/readiness`);
}
