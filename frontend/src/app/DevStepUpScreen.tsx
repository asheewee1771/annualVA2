import { useState } from "react";
import { confirmLocalStepUp } from "../api/dev.ts";
import { ApiError } from "../api/problem.ts";
import { describeAction } from "../api/stepUp.ts";
import { DEV_LOCAL_NOTICE } from "./devLocal.ts";

/** A return path is only ever a path of this site; anything else is the root. */
function safeReturn(candidate: string | null): string {
  return candidate !== null && candidate.startsWith("/") && !candidate.startsWith("//") ? candidate : "/";
}

/**
 * The local step-up confirmation of a developer's machine (ticket 63): where the back end sends the browser, in place
 * of the single sign-on's fresh sign-in, when a sensitive action needs one. Confirming makes the session fresh for this
 * action only; the action itself is not repeated, and the person is taken back to the screen they left.
 */
export function DevStepUpScreen() {
  const params = new URLSearchParams(window.location.search);
  const intent = params.get("intent") ?? "";
  const returnTo = safeReturn(params.get("returnTo"));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | undefined>(undefined);

  function confirm() {
    setBusy(true);
    setProblem(undefined);
    confirmLocalStepUp(intent).then(
      (done) => window.location.assign(`${safeReturn(done.returnTo)}?stepup=done`),
      (error: unknown) => {
        setProblem(
          error instanceof ApiError && error.problem.status === 409
            ? "That step-up is no longer waiting. Go back and start the action again."
            : "The confirmation could not be completed.",
        );
        setBusy(false);
      },
    );
  }

  return (
    <main id="main" className="shell-main">
      <div className="card">
        <p className="dev-banner" role="note">
          <strong>Local development sign-in.</strong> {DEV_LOCAL_NOTICE}
        </p>
        <h2>Confirm it is you</h2>
        <p>
          {describeAction(intent)} needs a sign-in from the last five minutes. Here that is a confirmation: there is no
          single sign-on to send you to.
        </p>
        {problem ? (
          <div className="error-panel" role="alert">
            <p>{problem}</p>
          </div>
        ) : null}
        <div className="interstitial__actions">
          <button type="button" className="button" disabled={busy} onClick={confirm}>
            Confirm
          </button>
          <a className="button button--secondary" href={returnTo}>
            Not now
          </a>
        </div>
      </div>
    </main>
  );
}
