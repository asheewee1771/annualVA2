import type { AreaProps } from "../shared/AreaProps.ts";
import { ExemptionRequestScreen } from "./ExemptionRequestScreen.tsx";
import { exemptionRequestOf, submissionRouteOf } from "./paths.ts";
import { ReworkScreen } from "./ReworkScreen.tsx";
import { SmoInboxScreen } from "./SmoInboxScreen.tsx";
import { SubmissionPrepareScreen } from "./SubmissionPrepareScreen.tsx";

/**
 * The SMO/Tech Lead's working area: the inbox and, beneath it, the screen where a VA Submission is prepared, the rework
 * queue of one Returned for Rework, and the Exemption request form.
 */
export function SmoArea({ path, classification }: AreaProps) {
  const exempting = exemptionRequestOf(path);
  if (exempting) {
    return <ExemptionRequestScreen key={exempting} systemId={exempting} classification={classification} />;
  }
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
