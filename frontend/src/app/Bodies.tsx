import type { Session } from "../api/session.ts";
import { LOCAL_STEP_UP_PATH } from "../api/stepUp.ts";
import { DevSignInScreen } from "./DevSignInScreen.tsx";
import { DevStepUpScreen } from "./DevStepUpScreen.tsx";
import { DEV_SIGN_IN_PATH } from "./devLocal.ts";
import { usePath } from "./router.ts";
import { SignedOutScreen } from "./SignedOutScreen.tsx";
import { Workspace } from "./Workspace.tsx";

/** What shows when there is no session: the single sign-on, or on `/dev-sign-in` the dev-local persona picker (ticket 63). */
export function SignedOutBody() {
  return usePath() === DEV_SIGN_IN_PATH ? <DevSignInScreen /> : <SignedOutScreen />;
}

/** What shows for a signed-in person: their workspace, or on `/dev-step-up` the dev-local step-up confirmation. */
export function SignedInBody({ session }: { session: Session }) {
  return usePath() === LOCAL_STEP_UP_PATH ? <DevStepUpScreen /> : <Workspace session={session} />;
}
