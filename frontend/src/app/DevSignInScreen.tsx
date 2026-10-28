import { useCallback, useState } from "react";
import { fetchPersonas, signInAsPersona, type DevPersona } from "../api/dev.ts";
import { ApiError } from "../api/problem.ts";
import { ErrorPanel } from "../components/ErrorPanel.tsx";
import { useResource } from "../hooks/useResource.ts";
import { AppLink } from "./AppLink.tsx";
import { DEV_LOCAL_NOTICE } from "./devLocal.ts";

/** What the person is told when a persona could not sign in. */
function failure(error: unknown): string {
  return error instanceof ApiError && error.problem.status === 403
    ? "The sign-in was refused. See the back end log."
    : "The sign-in could not be completed. Is the back end running with the dev-local profile?";
}

function PersonaButton({ persona, busy, onPick }: { persona: DevPersona; busy: boolean; onPick: () => void }) {
  return (
    <li>
      <button type="button" className="button persona" disabled={busy} onClick={onPick}>
        <strong>{persona.name}</strong>
        <span>{persona.roleLabel}</span>
        <span className="persona__email">{persona.email}</span>
      </button>
    </li>
  );
}

/**
 * The persona picker of a developer's machine (ticket 63). It is reachable at `/dev-sign-in` and works only where the
 * back end runs with the dev-local profile; anywhere else the list is a 404 and the screen says so. Picking a persona
 * signs in as it and reloads the app. The role a persona holds is whatever the System Register gives its email.
 */
export function DevSignInScreen() {
  const personas = useResource(useCallback(fetchPersonas, []));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | undefined>(undefined);

  function pick(persona: DevPersona) {
    setBusy(true);
    setProblem(undefined);
    signInAsPersona(persona.id).then(
      // A full load, so the session, the CSRF token and every screen start from the new person.
      () => window.location.assign("/"),
      (error: unknown) => {
        setProblem(failure(error));
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
        <h2>Sign in as a persona</h2>
        {personas.loading ? (
          <p className="muted" aria-live="polite">
            Loading…
          </p>
        ) : null}
        {personas.error ? (
          <>
            <ErrorPanel problem={personas.error.problem} />
            <p className="muted">
              The persona sign-in exists only when the back end runs with the dev-local profile. Use the{" "}
              <AppLink path="/">organisation single sign-on</AppLink> otherwise.
            </p>
          </>
        ) : null}
        {problem ? (
          <div className="error-panel" role="alert">
            <p>{problem}</p>
          </div>
        ) : null}
        {personas.data ? (
          <ul className="persona-list">
            {personas.data.map((persona) => (
              <PersonaButton key={persona.id} persona={persona} busy={busy} onPick={() => pick(persona)} />
            ))}
          </ul>
        ) : null}
        <p className="muted">
          A persona holds a role only when the System Register names its email. Upload the dev Register from{" "}
          <code>scripts/dev-data/</code> as a VA POC persona first (README, “Running without mock-m-sso”).
        </p>
      </div>
    </main>
  );
}
