import { useCallback, useEffect, useState } from "react";
import { setSignedOutHandler } from "../api/client.ts";
import { fetchSession, type Session } from "../api/session.ts";
import { ErrorPanel } from "../components/ErrorPanel.tsx";
import { useResource } from "../hooks/useResource.ts";
import { SignedInBody, SignedOutBody } from "./Bodies.tsx";
import { Shell } from "./Shell.tsx";
import { StepUpInterstitial } from "./StepUpInterstitial.tsx";
import { StepUpNotice } from "./StepUpNotice.tsx";
import { useStepUp } from "./useStepUp.ts";

/** Undefined while the session is still unknown (loading or failed), then whether the person is signed in. */
function signedInState(unknown: boolean, signedOut: boolean, session: Session | undefined): boolean | undefined {
  if (unknown) return undefined;
  return !signedOut && session?.authenticated === true;
}

/**
 * Session state machine: loading, signed-out, or signed-in. Any 401 from any call flips the app to
 * signed-out. The signed-in workspace (role navigation and landing areas) is mounted by Workspace; a
 * sensitive action that needs a fresh sign-in is explained over it and never repeated automatically.
 */
export function App() {
  const [signedOut, setSignedOut] = useState(false);
  const session = useResource(useCallback(fetchSession, []));
  const signedIn = signedInState(session.loading || session.error !== undefined, signedOut, session.data);
  const stepUp = useStepUp(signedIn);

  useEffect(() => {
    setSignedOutHandler(() => setSignedOut(true));
  }, []);

  if (session.loading) {
    return (
      <Shell session={undefined}>
        <main id="main" className="shell-main">
          <p className="muted" aria-live="polite">
            Loading…
          </p>
        </main>
      </Shell>
    );
  }

  if (session.error) {
    return (
      <Shell session={undefined}>
        <main id="main" className="shell-main">
          <ErrorPanel problem={session.error.problem} />
        </main>
      </Shell>
    );
  }

  if (signedIn !== true || !session.data) {
    // Keep only the classification once signed out: the header must not show a person who has no session.
    const classification = session.data?.classification;
    return (
      <Shell session={classification ? { authenticated: false, classification } : undefined}>
        <SignedOutBody />
      </Shell>
    );
  }

  return (
    <Shell session={session.data} onSignedOut={() => setSignedOut(true)}>
      {stepUp.returned ? <StepUpNotice outcome={stepUp.returned} onDismiss={stepUp.dismissReturned} /> : null}
      <SignedInBody session={session.data} />
      {stepUp.needed ? <StepUpInterstitial needed={stepUp.needed} onCancel={stepUp.dismissNeeded} /> : null}
    </Shell>
  );
}
