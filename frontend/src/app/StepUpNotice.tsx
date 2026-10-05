import { describeRefusal, type StepUpOutcome } from "../api/stepUp.ts";

interface StepUpNoticeProps {
  /** What the server reported on the way back, or undefined when the person only returned to the route they left. */
  outcome: StepUpOutcome | undefined;
  onDismiss: () => void;
}

/** Said once after the step-up round trip: what happened, and that the action still has to be repeated. */
export function StepUpNotice({ outcome, onDismiss }: StepUpNoticeProps) {
  const refused = outcome?.outcome === "refused";
  return (
    <div className={refused ? "notice notice--refused" : "notice"} role={refused ? "alert" : "status"}>
      <p>
        {refused ? (
          <>
            <strong>The sign-in was not accepted.</strong> {describeRefusal(outcome?.reason)} Try the action again to be
            sent back to sign in.
          </>
        ) : (
          <>
            <strong>You signed in again.</strong> The action was not repeated: do it again to finish.
          </>
        )}
      </p>
      <button type="button" className="button button--secondary" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  );
}
