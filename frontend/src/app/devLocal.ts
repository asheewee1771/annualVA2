/** The routes of the dev-local profile's screens (ticket 63): the persona picker and the local step-up confirmation. */
export const DEV_SIGN_IN_PATH = "/dev-sign-in";
export const DEV_STEP_UP_PATH = "/dev-step-up";

/** Said on every dev-local screen, so nobody mistakes it for the real single sign-on. */
export const DEV_LOCAL_NOTICE =
  "This is not the organisation single sign-on: anyone who can reach this application can sign in as any persona. " +
  "It works only on a developer's machine.";
