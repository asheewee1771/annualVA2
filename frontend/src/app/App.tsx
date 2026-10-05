import { useCallback, useEffect, useState } from "react";
import { setSignedOutHandler } from "../api/client.ts";
import { fetchSession } from "../api/session.ts";
import { ErrorPanel } from "../components/ErrorPanel.tsx";
import { useResource } from "../hooks/useResource.ts";
import { Shell } from "./Shell.tsx";
import { SignedOutScreen } from "./SignedOutScreen.tsx";
import { Workspace } from "./Workspace.tsx";

/**
 * Session state machine: loading, signed-out, or signed-in. Any 401 from any call flips the app to
 * signed-out. The signed-in workspace (role navigation and landing areas) is mounted by Workspace.
 */
export function App() {
  const [signedOut, setSignedOut] = useState(false);
  const session = useResource(useCallback(fetchSession, []));

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

  if (signedOut || !session.data?.authenticated) {
    // Keep only the classification once signed out: the header must not show a person who has no session.
    const classification = session.data?.classification;
    return (
      <Shell session={classification ? { authenticated: false, classification } : undefined}>
        <SignedOutScreen />
      </Shell>
    );
  }

  return (
    <Shell session={session.data} onSignedOut={() => setSignedOut(true)}>
      <Workspace session={session.data} />
    </Shell>
  );
}
