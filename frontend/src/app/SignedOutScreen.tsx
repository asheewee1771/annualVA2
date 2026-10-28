import { useCallback } from "react";
import { fetchPersonas } from "../api/dev.ts";
import { useResource } from "../hooks/useResource.ts";
import { AppLink } from "./AppLink.tsx";
import { DEV_SIGN_IN_PATH } from "./devLocal.ts";

/** What the person is told after a refused sign-in; the reason code in the URL selects the text, never shows. */
const REFUSALS: Record<string, string> = {
  "mfa-not-evidenced":
    "Multi-factor authentication is required at every sign-in. Sign in again with your second factor.",
  "missing-identity": "Your sign-in did not identify you. Contact the VA POC.",
  "missing-auth-time": "Your sign-in could not be verified as recent. Sign in again.",
  "identity-conflict":
    "Your sign-in could not be linked to your account because another account already uses your email address. Contact the VA POC.",
  "email-not-verified":
    "Your email address is not verified at the single sign-on. Verify it there, then sign in again.",
  "missing-email": "Your sign-in carried no email address. Contact the VA POC.",
  "account-disabled":
    "The single sign-on reports your account disabled, so your roles here are suspended. Contact the VA POC once it is enabled again.",
};
const REFUSED_FALLBACK = "The sign-in could not be completed. Try again, and tell the VA POC if it keeps happening.";

function refusal(): string | undefined {
  const params = new URLSearchParams(window.location.search);
  if (params.get("signin") !== "refused") return undefined;
  const reason = params.get("reason") ?? "";
  // The code comes from the URL: look it up as an own key only, so "__proto__" and the like fall back.
  return Object.hasOwn(REFUSALS, reason) ? REFUSALS[reason] : REFUSED_FALLBACK;
}

/**
 * Offered only where the back end answers the persona list (the dev-local profile of a developer's machine, ticket 63):
 * anywhere else that is a 404 and nothing is shown.
 */
function DevSignInOffer() {
  const personas = useResource(useCallback(fetchPersonas, []));
  if (!personas.data) return null;
  return (
    <p className="dev-banner" role="note">
      <strong>Local development:</strong> <AppLink path={DEV_SIGN_IN_PATH}>Sign in as a persona</AppLink> instead of the
      single sign-on.
    </p>
  );
}

/** Shown when there is no session. Sign-in goes through the organisation single sign-on. */
export function SignedOutScreen() {
  const refused = refusal();
  return (
    <main id="main" className="shell-main">
      <div className="card">
        <h2>Sign in to Annual VA</h2>
        {refused ? (
          <div className="error-panel" role="alert">
            <strong>Sign-in refused</strong>
            <p>{refused}</p>
          </div>
        ) : null}
        <p className="muted">
          Use the organisation single sign-on. Multi-factor authentication is required at every sign-in.
        </p>
        <a className="button" href="/oauth2/authorization/mcc-sso-login">
          Sign in
        </a>
        <DevSignInOffer />
      </div>
    </main>
  );
}
