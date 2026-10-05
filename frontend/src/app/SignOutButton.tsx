import { useState } from "react";
import { ApiError } from "../api/problem.ts";
import { signOut } from "../api/session.ts";

/** Ends the session on the server, then tells the app it is signed out. A failure is said, not hidden. */
export function SignOutButton({ onSignedOut }: { onSignedOut: () => void }) {
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  function handleClick() {
    setBusy(true);
    setFailed(false);
    signOut().then(onSignedOut, (error: unknown) => {
      // A 401 already returned the app to the signed-out screen through the client.
      if (!(error instanceof ApiError) || error.problem.status !== 401) setFailed(true);
      setBusy(false);
    });
  }

  return (
    <span className="shell-header__session">
      {failed ? (
        <span className="muted" role="alert">
          Could not sign out. Try again.
        </span>
      ) : null}
      <button type="button" className="button button--secondary" disabled={busy} onClick={handleClick}>
        Sign out
      </button>
    </span>
  );
}
