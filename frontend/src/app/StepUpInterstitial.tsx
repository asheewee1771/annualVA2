import { useEffect, useRef } from "react";
import { describeAction, saveForStepUp, stepUpUrl, type StepUpNeeded } from "../api/stepUp.ts";
import { usePath } from "./router.ts";

interface StepUpInterstitialProps {
  needed: StepUpNeeded;
  onCancel: () => void;
}

/**
 * Explains why a sensitive action stopped (story 77) and offers the fresh sign-in. The screen behind
 * stays mounted, so nothing typed is lost if the person decides not to continue. Choosing to sign in
 * again saves the route and any draft first; the action itself is never repeated for them.
 */
export function StepUpInterstitial({ needed, onCancel }: StepUpInterstitialProps) {
  const dialog = useRef<HTMLDialogElement>(null);
  const route = usePath();

  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) element.showModal();
  }, []);

  return (
    <dialog ref={dialog} className="interstitial" aria-labelledby="step-up-title" onCancel={onCancel}>
      <h2 id="step-up-title">Sign in again to continue</h2>
      <p>
        {describeAction(needed.intent)} needs a sign-in from the last five minutes, because it changes data that cannot
        be undone and the reports are sensitive. If someone else had taken over your session, this stops them.
      </p>
      <p className="muted">
        You will go to the organisation single sign-on and come back to this screen. What you were doing is kept, and
        the action is not repeated for you: when you are back, do it again.
      </p>
      <div className="interstitial__actions">
        {/* A plain link: the browser leaves for the single sign-on, so nothing here needs to wait for a reply. */}
        <a className="button" href={stepUpUrl(needed, route)} onClick={() => saveForStepUp(needed, route)}>
          Sign in again
        </a>
        <button type="button" className="button button--secondary" onClick={onCancel}>
          Not now
        </button>
      </div>
    </dialog>
  );
}
