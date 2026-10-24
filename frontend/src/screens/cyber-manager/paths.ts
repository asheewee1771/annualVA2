/** The Cyber Manager's working area: the inbox. */
export const CM_INBOX_PATH = "/cyber-manager";

/** The Quarter board tab. */
export const CM_BOARD_PATH = "/cyber-manager/quarter-board";

/** Where the Cyber Manager reviews a VA Submission. */
export function reviewPath(submissionId: string): string {
  return `${CM_INBOX_PATH}/submissions/${submissionId}`;
}

const REVIEW_PATH = /^\/cyber-manager\/submissions\/([0-9a-fA-F-]{36})\/?$/;

/** The VA Submission a path reviews, or undefined when the path is not a review's. */
export function reviewedSubmissionOf(path: string): string | undefined {
  return REVIEW_PATH.exec(path)?.[1];
}
