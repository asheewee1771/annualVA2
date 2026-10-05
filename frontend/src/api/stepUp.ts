/**
 * Step-up on the browser side (story 77, ADR 0004). A sensitive command the server refuses with
 * `step-up-required` costs the person a fresh sign-in; this module keeps where they were, and any form
 * draft, across that round trip in sessionStorage (this tab only) and reads the
 * outcome the server puts in the URL when it sends them back. Nothing kept outlives its use: it is dropped
 * when the app finds there is no session (sign-out, a 401, expiry), when a page loads without a step-up
 * return, and when it is older than a round trip. The refused command is never replayed.
 */

import type { Problem } from "./problem.ts";

const ROUTE_KEY = "annualva.stepUp.route";
const DRAFT_KEY = "annualva.stepUp.draft";

/** What a refused sensitive command needs the person to do next. */
export interface StepUpNeeded {
  /** The server's path that starts the fresh sign-in, with the intent already in it. */
  stepUpPath: string;
  /** The fixed code of the action, such as `register-apply`. */
  intent: string;
  /** The JSON the person was submitting, if any (never a file upload), so a form can be refilled. */
  draft?: unknown;
}

/** The result the server reports on the way back: `?stepup=done` or `?stepup=refused&reason=<code>`. */
export interface StepUpOutcome {
  outcome: "done" | "refused";
  reason?: string;
}

/** Plain words for the actions that need a step-up; an action not listed here is "This action". */
const ACTIONS: Readonly<Record<string, string>> = {
  "register-apply": "Applying the System Register",
};

export function describeAction(intent: string): string {
  return (Object.hasOwn(ACTIONS, intent) ? ACTIONS[intent] : undefined) ?? "This action";
}

/** The refused-step-up wording, by the server's fixed reason code. */
const REFUSALS: Readonly<Record<string, string>> = {
  "stale-auth-time": "The single sign-on did not ask you to sign in again, so the sign-in was not accepted as new.",
  "missing-auth-time": "Your sign-in could not be verified as recent.",
  "mfa-not-evidenced": "Multi-factor authentication was not shown at that sign-in.",
  "identity-mismatch": "You signed in as a different person than the one using this session.",
  "session-ended": "Your session ended before the sign-in finished.",
};

export function describeRefusal(reason: string | undefined): string {
  return (
    (reason !== undefined && Object.hasOwn(REFUSALS, reason) ? REFUSALS[reason] : undefined) ??
    "The sign-in could not be completed."
  );
}

/** Where to send the browser: the server's step-up path, asking to come back to this route. */
export function stepUpUrl(needed: StepUpNeeded, route: string): string {
  return `${needed.stepUpPath}&returnTo=${encodeURIComponent(route)}`;
}

/** A step-up round trip takes seconds to minutes; anything older was abandoned and is never restored. */
const MAX_AGE_MS = 15 * 60 * 1000;

interface Kept<T> {
  value: T;
  savedAt: number;
}

function store(key: string, value: unknown): void {
  try {
    const kept: Kept<unknown> = { value, savedAt: Date.now() };
    window.sessionStorage.setItem(key, JSON.stringify(kept));
  } catch {
    // Storage can be blocked: the person then returns to the server's return path and starts again.
  }
}

/** Reads and removes an entry; an unreadable, damaged or abandoned (too old) one counts as nothing saved. */
function take(key: string): unknown {
  try {
    const raw = window.sessionStorage.getItem(key);
    window.sessionStorage.removeItem(key);
    if (raw === null) return undefined;
    const kept = JSON.parse(raw) as Partial<Kept<unknown>>;
    return typeof kept.savedAt === "number" && Date.now() - kept.savedAt <= MAX_AGE_MS ? kept.value : undefined;
  } catch {
    return undefined;
  }
}

/** Remembers the route and any draft before the browser leaves for the single sign-on. */
export function saveForStepUp(needed: StepUpNeeded, route: string): void {
  store(ROUTE_KEY, route);
  if (needed.draft !== undefined) store(DRAFT_KEY, { intent: needed.intent, draft: needed.draft });
  else clearKey(DRAFT_KEY);
}

function clearKey(key: string): void {
  try {
    window.sessionStorage.removeItem(key);
  } catch {
    // Nothing to clear if storage is blocked.
  }
}

/** Forgets everything kept for a step-up. The app calls this whenever it finds there is no session. */
export function clearStepUpState(): void {
  clearKey(ROUTE_KEY);
  clearKey(DRAFT_KEY);
}

/** The draft saved for this action, read once and forgotten by the form that owns the action. */
// fallow-ignore-next-line unused-export
export function takeRestoredDraft(intent: string): unknown {
  const saved = take(DRAFT_KEY);
  return typeof saved === "object" && saved !== null && "intent" in saved && saved.intent === intent && "draft" in saved
    ? saved.draft
    : undefined;
}

/** The outcome in the address bar, removed from it so a reload does not show it again. */
function takeStepUpOutcome(): StepUpOutcome | undefined {
  const params = new URLSearchParams(window.location.search);
  const outcome = params.get("stepup");
  if (outcome !== "done" && outcome !== "refused") return undefined;
  const reason = params.get("reason") ?? undefined;
  params.delete("stepup");
  params.delete("reason");
  const rest = params.toString();
  window.history.replaceState(null, "", window.location.pathname + (rest ? `?${rest}` : ""));
  return { outcome, ...(reason !== undefined ? { reason } : {}) };
}

/** Where the person was, as a path of this site only. */
function takeSavedRoute(): string | undefined {
  const route = take(ROUTE_KEY);
  return typeof route === "string" && route.startsWith("/") && !route.startsWith("//") ? route : undefined;
}

/** What the server's redirect brought the person back with. */
export interface StepUpArrival extends StepUpOutcome {
  /** The route they left, when it was saved recently enough to be this round trip's. */
  route?: string;
}

/**
 * Called once as the page loads. Only a return that carries the server's `?stepup=` outcome is a step-up
 * return, and only then is the saved route restored; a saved route with no such return, or an abandoned
 * one, is dropped so it is never restored as if a step-up had just happened. Read before anything can
 * rewrite the address, so a redirect cannot lose the outcome.
 */
export function takeStepUpArrival(): StepUpArrival | undefined {
  const outcome = takeStepUpOutcome();
  const route = takeSavedRoute();
  if (outcome === undefined) {
    clearStepUpState();
    return undefined;
  }
  return { ...outcome, ...(route !== undefined ? { route } : {}) };
}

/** Reads a `step-up-required` problem into what the app needs; anything else is not a step-up. */
export function stepUpNeededFrom(problem: Problem, body: unknown): StepUpNeeded | undefined {
  if (!problem.type.endsWith("/step-up-required") || problem.stepUpPath === undefined) return undefined;
  const intent = new URL(problem.stepUpPath, window.location.origin).searchParams.get("intent");
  if (intent === null) return undefined;
  // A file upload cannot be kept; only JSON the person typed can.
  return {
    stepUpPath: problem.stepUpPath,
    intent,
    ...(body === undefined || body instanceof FormData ? {} : { draft: body }),
  };
}
