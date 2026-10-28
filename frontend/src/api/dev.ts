import { request } from "./client.ts";

/**
 * The dev-local profile's endpoints (ticket 63, ADR 0058). They exist only on a developer's machine: everywhere else
 * `GET /api/dev/personas` answers 404, which is how the app knows not to offer them.
 */

/** One of the six personas a developer can sign in as. The role label says what the dev Register gives it: it grants nothing. */
export interface DevPersona {
  id: string;
  name: string;
  roleLabel: string;
  email: string;
}

export function fetchPersonas(): Promise<DevPersona[]> {
  return request<DevPersona[]>("GET", "/api/dev/personas");
}

/** POST /api/dev/sign-in: signs in as the persona; the answer is 204 and the session cookie. */
export function signInAsPersona(persona: string): Promise<void> {
  return request<void>("POST", "/api/dev/sign-in", { persona });
}

/** POST /api/dev/step-up: confirms the step-up that was started for this action; says where to go back to. */
export function confirmLocalStepUp(intent: string): Promise<{ returnTo: string }> {
  return request<{ returnTo: string }>("POST", "/api/dev/step-up", { intent });
}
