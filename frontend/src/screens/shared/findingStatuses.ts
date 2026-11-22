import {
  MAX_NAME_LENGTH,
  MAX_TEXT_LENGTH,
  type FindingStatus,
  type Proposal,
  type ProposalInput,
  type ReviewState,
  type Severity,
} from "../../api/findings.ts";

export type FieldName =
  | "evidence"
  | "justification"
  | "reason"
  | "approverName"
  | "approverRole"
  | "approvalMemoFileId"
  | "plan"
  | "targetDate";

/** One input a status asks for: how it is labelled and drawn, and its limit. */
export interface FieldSpec {
  name: FieldName;
  label: string;
  /** A long text, a one-line text, a date, or the choice of an approval memo among the evidence files. */
  kind: "text" | "line" | "date" | "memo";
  maxLength?: number;
  required: boolean;
}

export interface StatusSpec {
  value: FindingStatus;
  label: string;
  fields: readonly FieldSpec[];
}

/** The four Finding Statuses, each with the fields it requires (story 35). The server enforces them again. */
export const STATUSES: readonly StatusSpec[] = [
  {
    value: "REMEDIATED",
    label: "Remediated",
    fields: [{ name: "evidence", label: "Evidence", kind: "text", maxLength: MAX_TEXT_LENGTH, required: true }],
  },
  {
    value: "FALSE_POSITIVE",
    label: "False Positive",
    fields: [
      { name: "justification", label: "Justification", kind: "text", maxLength: MAX_TEXT_LENGTH, required: true },
    ],
  },
  {
    value: "RISK_ACCEPTED",
    label: "Risk Accepted",
    fields: [
      { name: "reason", label: "Reason", kind: "text", maxLength: MAX_TEXT_LENGTH, required: true },
      { name: "approverName", label: "Approver name", kind: "line", maxLength: MAX_NAME_LENGTH, required: true },
      { name: "approverRole", label: "Approver role", kind: "line", maxLength: MAX_NAME_LENGTH, required: true },
      { name: "approvalMemoFileId", label: "Approval memo (optional)", kind: "memo", required: false },
    ],
  },
  {
    value: "REMEDIATION_PLANNED",
    label: "Remediation Planned",
    fields: [
      { name: "plan", label: "Plan", kind: "text", maxLength: MAX_TEXT_LENGTH, required: true },
      { name: "targetDate", label: "Target date", kind: "date", required: true },
    ],
  },
];

export function statusSpec(status: FindingStatus): StatusSpec {
  return STATUSES.find((spec) => spec.value === status) ?? STATUSES[0]!;
}

export const REVIEW_LABELS: Readonly<Record<ReviewState, string>> = {
  PENDING: "Awaiting review",
  CONFIRMED: "Confirmed",
  DISPUTED: "Disputed",
};

/**
 * What a proposal the SMO/Tech Lead did not write themselves is marked with: one a rescan made, or one the deletion of the
 * file that last reported the Finding made (ticket 60).
 */
export const PROPOSED_AS_LABELS: Readonly<Record<Exclude<Proposal["proposedAs"], "SMO">, string>> = {
  RESCAN: "Rescan",
  FILE_DELETED: "File deleted",
};

export const SEVERITY_LABELS: Readonly<Record<Severity, string>> = {
  CRITICAL: "Critical",
  HIGH: "High",
  MEDIUM: "Medium",
  LOW: "Low",
  INFORMATIONAL: "Informational",
};

/** What to send for the status: its fields as typed, and nothing for an optional one left empty. */
export function inputFor(spec: StatusSpec, values: Readonly<Partial<Record<FieldName, string>>>): ProposalInput {
  const input: ProposalInput = { status: spec.value };
  for (const field of spec.fields) {
    const value = values[field.name] ?? "";
    if (value !== "" || field.required) input[field.name] = value;
  }
  return input;
}

const text = (value: string | null): string => value ?? "";

const DETAILS: Readonly<Record<FindingStatus, (proposal: Proposal) => string>> = {
  REMEDIATED: (proposal) => text(proposal.evidence),
  FALSE_POSITIVE: (proposal) => text(proposal.justification),
  RISK_ACCEPTED: (proposal) =>
    `${text(proposal.reason)} (accepted by ${text(proposal.approverName)}, ${text(proposal.approverRole)}` +
    `${proposal.approvalMemoFileId ? "; approval memo attached" : ""})`,
  REMEDIATION_PLANNED: (proposal) => `${text(proposal.plan)} (target date ${text(proposal.targetDate)})`,
};

/** The words of a proposal that say what was proposed, for the list; every text is shown as text. */
export function proposalDetail(proposal: Proposal): string {
  return DETAILS[proposal.status](proposal);
}

/** A Critical or High Finding is flagged as a danger. */
export function severityClass(severity: Severity): string {
  return severity === "CRITICAL" || severity === "HIGH" ? "flag flag--danger" : "flag";
}
