import { useEffect, useState } from "react";
import { setStepUpRequiredHandler } from "../api/client.ts";
import { clearStepUpState, stepUpArrival, type StepUpNeeded, type StepUpOutcome } from "../api/stepUp.ts";
import { navigate } from "./router.ts";

/**
 * The app's side of step-up (story 77): `needed` is set when a sensitive command is refused for want
 * of a fresh sign-in, and `returned` once the person is back from the round trip, on the route they
 * left. The refused action is never run again from here.
 *
 * @param signedIn undefined while the session is loading, then whether there is one. Finding there is
 *     none (sign-out, a 401, an expired session) drops what was kept for a step-up.
 */
export function useStepUp(signedIn: boolean | undefined) {
  const [needed, setNeeded] = useState<StepUpNeeded | undefined>(undefined);
  const [returned, setReturned] = useState<StepUpOutcome | undefined>(undefined);

  useEffect(() => {
    setStepUpRequiredHandler(setNeeded);
  }, []);

  useEffect(() => {
    // The arrival was read as the page loaded; whether the person is signed in is only known now.
    const arrival = stepUpArrival();
    if (signedIn === false) {
      clearStepUpState();
    } else if (signedIn === true && arrival) {
      if (arrival.route !== undefined) navigate(arrival.route, true);
      setReturned({ outcome: arrival.outcome, ...(arrival.reason !== undefined ? { reason: arrival.reason } : {}) });
    }
  }, [signedIn]);

  return {
    needed,
    dismissNeeded: () => setNeeded(undefined),
    returned,
    dismissReturned: () => setReturned(undefined),
  };
}
