/** The SMO/Tech Lead's working area. */
export const SMO_AREA_PATH = "/smo";

/** Where a VA Submission is prepared. */
export function submissionPath(submissionId: string): string {
  return `${SMO_AREA_PATH}/submissions/${submissionId}`;
}

const SUBMISSION_PATH = /^\/smo\/submissions\/([0-9a-fA-F-]{36})\/?$/;

/** The VA Submission a path prepares, or undefined when the path is not a submission's. */
export function submissionIdOf(path: string): string | undefined {
  return SUBMISSION_PATH.exec(path)?.[1];
}
