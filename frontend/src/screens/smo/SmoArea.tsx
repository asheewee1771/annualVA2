import type { AreaProps } from "../shared/AreaProps.ts";
import { submissionRouteOf } from "./paths.ts";
import { ReworkScreen } from "./ReworkScreen.tsx";
import { SmoInboxScreen } from "./SmoInboxScreen.tsx";
import { SubmissionPrepareScreen } from "./SubmissionPrepareScreen.tsx";

/**
 * The SMO/Tech Lead's working area: the inbox and, beneath it, the screen where a VA Submission is prepared and the rework
 * queue of one Returned for Rework.
 */
export function SmoArea({ path, classification }: AreaProps) {
  const route = submissionRouteOf(path);
  if (route?.rework) {
    return <ReworkScreen key={route.submissionId} submissionId={route.submissionId} classification={classification} />;
  }
  if (route) {
    return (
      <SubmissionPrepareScreen
        key={route.submissionId}
        submissionId={route.submissionId}
        classification={classification}
      />
    );
  }
  return <SmoInboxScreen />;
}
