/** The Cyber Manager's working area: the inbox. */
export const CM_INBOX_PATH = "/cyber-manager";

/** The Quarter board tab. */
export const CM_BOARD_PATH = "/cyber-manager/quarter-board";

/** The audit history tab: the history of every VA Submission the Cyber Manager reviewed (story 85). */
export const CM_AUDIT_PATH = "/cyber-manager/audit-history";

/** Where the Cyber Manager reviews a VA Submission. */
export function reviewPath(submissionId: string): string {
  return `${CM_INBOX_PATH}/submissions/${submissionId}`;
}

const REVIEW_PATH = /^\/cyber-manager\/submissions\/([0-9a-fA-F-]{36})\/?$/;

/** The VA Submission a path reviews, or undefined when the path is not a review's. */
export function reviewedSubmissionOf(path: string): string | undefined {
  return REVIEW_PATH.exec(path)?.[1];
}

/** The audit history of one VA Submission the Cyber Manager reviewed. */
export function auditHistoryPath(submissionId: string): string {
  return `${reviewPath(submissionId)}/audit-history`;
}

const AUDIT_HISTORY_PATH = /^\/cyber-manager\/submissions\/([0-9a-fA-F-]{36})\/audit-history\/?$/;

/** The VA Submission whose audit history a path shows, or undefined when the path is not one. */
export function auditedSubmissionOf(path: string): string | undefined {
  return AUDIT_HISTORY_PATH.exec(path)?.[1];
}
