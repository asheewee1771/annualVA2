/** The SMO/Tech Lead's working area. */
export const SMO_AREA_PATH = "/smo";

/** Where a VA Submission is prepared. */
export function submissionPath(submissionId: string): string {
  return `${SMO_AREA_PATH}/submissions/${submissionId}`;
}

/** Where a VA Submission Returned for Rework is worked through, one Finding at a time (story 60). */
export function reworkPath(submissionId: string): string {
  return `${submissionPath(submissionId)}/rework`;
}

const SUBMISSION_PATH = /^\/smo\/submissions\/([0-9a-fA-F-]{36})(\/rework)?\/?$/;

/** The VA Submission a path is about and whether it is its rework queue, or undefined when the path is not a submission's. */
export function submissionRouteOf(path: string): { submissionId: string; rework: boolean } | undefined {
  const match = SUBMISSION_PATH.exec(path);
  return match?.[1] ? { submissionId: match[1], rework: match[2] !== undefined } : undefined;
}
