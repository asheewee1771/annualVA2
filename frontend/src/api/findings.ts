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

/**
 * GET /api/submissions/{id}/findings: a page of the VA Submission's Findings, the most severe first, with each one's current
 * proposal and review state. The server also narrows by report type, severity, review state and "needs attention".
 */
export function fetchFindings(submissionId: string, page: number, size: number): Promise<FindingPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return request<FindingPage>(
    "GET",
    `/api/submissions/${encodeURIComponent(submissionId)}/findings?${query.toString()}`,
  );
}

/**
 * POST /api/findings/{id}/proposals: the SMO/Tech Lead proposes a Finding Status with the field it requires. Every proposal
 * is kept; the Finding's current proposal changes and its review goes back to pending. A field that is wrong is a 400 that
 * names it.
 */
export function proposeStatus(findingId: string, input: ProposalInput): Promise<Proposal> {
  return request<Proposal>("POST", `/api/findings/${encodeURIComponent(findingId)}/proposals`, input);
}
