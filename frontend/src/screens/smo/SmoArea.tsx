import type { AreaProps } from "../shared/AreaProps.ts";
import { submissionIdOf } from "./paths.ts";
import { SmoInboxScreen } from "./SmoInboxScreen.tsx";
import { SubmissionPrepareScreen } from "./SubmissionPrepareScreen.tsx";

/** The SMO/Tech Lead's working area: the inbox and, beneath it, the screen where a VA Submission is prepared. */
export function SmoArea({ path, classification }: AreaProps) {
  const submissionId = submissionIdOf(path);
  if (submissionId) {
    return <SubmissionPrepareScreen key={submissionId} submissionId={submissionId} classification={classification} />;
  }
  return <SmoInboxScreen classification={classification} />;
}
