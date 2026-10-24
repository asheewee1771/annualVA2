/** The VA POC's working area: the Quarter board. */
export const VA_POC_PATH = "/va-poc";

/** Where a VA POC reads a VA Submission and endorses it. */
export function endorsePath(submissionId: string): string {
  return `${VA_POC_PATH}/submissions/${submissionId}`;
}

const ENDORSE_PATH = /^\/va-poc\/submissions\/([0-9a-fA-F-]{36})\/?$/;

/** The VA Submission a path opens for endorsement, or undefined when the path is not one. */
export function endorsedSubmissionOf(path: string): string | undefined {
  return ENDORSE_PATH.exec(path)?.[1];
}
