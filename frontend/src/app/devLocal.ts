/**
 * The route of the dev-local persona picker (ticket 63). The local step-up confirmation's route is the server's to name
 * and is `LOCAL_STEP_UP_PATH` of `api/stepUp.ts`.
 */
export const DEV_SIGN_IN_PATH = "/dev-sign-in";

/** Said on every dev-local screen, so nobody mistakes it for the real single sign-on. */
export const DEV_LOCAL_NOTICE =
  "This is not the organisation single sign-on: anyone who can reach this application can sign in as any persona. " +
  "It works only on a developer's machine.";
