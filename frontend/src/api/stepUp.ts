/**
 * Step-up on the browser side (story 77, ADR 0004). A sensitive command the server refuses with
 * `step-up-required` costs the person a fresh sign-in; this module keeps where they were, and any form
 * draft, across that round trip in sessionStorage (this tab only) and reads the
 * outcome the server puts in the URL when it sends them back. What is kept stays in sessionStorage
 * only until a page load takes it out (`readLanding` says which loads do). It is restored only if that load is
 * a step-up return, and dropped when the app finds there is no session (sign-out, a 401, expiry) or when it is
 * older than a round trip. The refused command is never replayed.
 */

import type { Problem } from "./problem.ts";

/**
 * The page of this app the server's local step-up redirects to before the return, where a confirmation stands in for
 * the single sign-on's fresh sign-in (`DevLocalStepUp.CONFIRMATION_PAGE`, ADR 0058 item 4). The server names it, so it
 * is kept here with the rest of the step-up round trip, and the app's router takes it from here.
 */
export const LOCAL_STEP_UP_PATH = "/dev-step-up";

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
  "submission-endorse": "Endorsing a VA Submission",
  "cycle-close": "Closing a VA Cycle",
  "submission-return": "Returning a VA Submission for rework",
  "export-mgmt-summary": "Asking for the management summary",
  "exemption-decide": "Deciding an Exemption",
  "disposal-approve": "Approving the disposal list",
  "role-suspend": "Suspending a role",
  "role-restore": "Restoring a suspended role",
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
  "account-disabled": "The single sign-on reports your account disabled, so your roles here are suspended.",
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

/** Reads an entry; an unreadable, damaged or abandoned (too old) one counts as nothing saved. */
function read(key: string): unknown {
  try {
    const raw = window.sessionStorage.getItem(key);
    if (raw === null) return undefined;
    const kept = JSON.parse(raw) as Partial<Kept<unknown>>;
    return typeof kept.savedAt === "number" && Date.now() - kept.savedAt <= MAX_AGE_MS ? kept.value : undefined;
  } catch {
    return undefined;
  }
}

/** Reads and removes an entry. */
function take(key: string): unknown {
  const value = read(key);
  clearKey(key);
  return value;
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
  delete landing.draft;
}

/** A form's draft as it was saved, with the action it belongs to. */
interface SavedDraft {
  intent: string;
  draft: unknown;
}

function takeSavedDraft(): SavedDraft | undefined {
  const saved = take(DRAFT_KEY);
  if (typeof saved !== "object" || saved === null || !("intent" in saved)) return undefined;
  const intent = saved.intent;
  return typeof intent === "string" ? { intent, draft: "draft" in saved ? saved.draft : undefined } : undefined;
}

/**
 * The draft this page load's step-up return restored for this action, handed once to the form that owns the action. A
 * draft for another action is left for its own form. It is held in memory only, so it is gone with the page.
 */
export function takeRestoredDraft(intent: string): unknown {
  const restored = landing.draft;
  if (restored?.intent !== intent) return undefined;
  delete landing.draft;
  return restored.draft;
}

/** Whether a refusal is the one the app answers with its step-up explanation, so a screen need not show it again. */
export function isStepUpRequired(problem: Problem): boolean {
  return problem.type.endsWith("/step-up-required");
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

/**
 * Whether a string is a path of this site and nothing else: the same characters the server allows in a return path
 * (`StepUp.safeReturnPath`), so no backslash, scheme, host or query can pass for one.
 */
export function isSafeRoute(candidate: string | null): candidate is string {
  return candidate !== null && /^\/[A-Za-z0-9._~/-]{0,199}$/.test(candidate) && !candidate.startsWith("//");
}

/** Where the person was, as a path of this site only. */
function takeSavedRoute(): string | undefined {
  const route = take(ROUTE_KEY);
  return typeof route === "string" && isSafeRoute(route) ? route : undefined;
}

/** What the server's redirect brought the person back with. */
export interface StepUpArrival extends StepUpOutcome {
  /** The route they left, when it was saved recently enough to be this round trip's. */
  route?: string;
}

/** What a page load found: the step-up return it was, if it was one, and the draft that return restored. */
interface Landing {
  arrival?: StepUpArrival;
  draft?: SavedDraft;
}

/**
 * Whether this page load is the middle of a round trip: the local step-up page, which the server shows before the
 * return. The browser cannot tell which sign-in the server uses, so this is decided by the address alone and holds
 * wherever the app runs. That loosens nothing: this load restores nothing, and what it leaves saved is still restored
 * only by a load that carries the server's `?stepup=` outcome and still dropped by any other load, by its age and by
 * the loss of the session.
 */
function midRoundTrip(): boolean {
  return window.location.pathname === LOCAL_STEP_UP_PATH;
}

/**
 * The one read of a page load. It takes the outcome out of the address and the saved route and draft out of
 * sessionStorage together, so after it nothing is left saved. Only a load that carries the server's `?stepup=` outcome
 * is a step-up return, and only then are the route and draft kept (in memory, for this page); with no such return, or
 * when they were abandoned, they are dropped, so they are never restored as if a step-up had just happened. The middle
 * of a round trip is the one load that leaves everything where it is, for the return that follows.
 */
function readLanding(): Landing {
  if (midRoundTrip()) return {};
  const outcome = takeStepUpOutcome();
  const route = takeSavedRoute();
  const draft = takeSavedDraft();
  if (outcome === undefined) return {};
  return {
    arrival: { ...outcome, ...(route !== undefined ? { route } : {}) },
    ...(draft !== undefined ? { draft } : {}),
  };
}

/**
 * Read as this module loads. Every module that asks for the arrival or a draft imports this one, so the read is done
 * before any of them runs and before anything can rewrite the address: a redirect cannot lose the outcome, and the
 * order in which the askers load does not matter.
 */
const landing = readLanding();

/** This page load's step-up return, if it was one. The same answer however often it is asked. */
export function stepUpArrival(): StepUpArrival | undefined {
  return landing.arrival;
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
