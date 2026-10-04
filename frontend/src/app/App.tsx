import { useCallback, useEffect, useState } from "react";
import { setSignedOutHandler } from "../api/client.ts";
import { fetchSession } from "../api/session.ts";
import { ErrorPanel } from "../components/ErrorPanel.tsx";
import { useResource } from "../hooks/useResource.ts";
import { Shell } from "./Shell.tsx";
import { SignedOutScreen } from "./SignedOutScreen.tsx";

/**
 * Session state machine: loading, signed-out, or signed-in. Any 401 from any call flips the app to
 * signed-out. Role screens mount here once the access module exists.
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
    return (
      <Shell session={session.data}>
        <SignedOutScreen />
      </Shell>
    );
  }

  return (
    <Shell session={session.data}>
      <main id="main" className="shell-main">
        <div className="card">
          <h2>Welcome</h2>
          <p className="muted">Your dashboard appears here once roles are assigned.</p>
        </div>
      </main>
    </Shell>
  );
}
