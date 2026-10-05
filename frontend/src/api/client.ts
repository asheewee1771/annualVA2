import type { Download } from "./files.ts";
import { ApiError, problemFrom } from "./problem.ts";
import { stepUpNeededFrom, type StepUpNeeded } from "./stepUp.ts";

/**
 * The one place the SPA talks HTTP. Same-origin cookies carry the session; state-changing calls
 * carry the synchronizer CSRF token bootstrapped from GET /api/csrf. Non-2xx responses become
 * ApiError; a 401 also tells the app the session is gone.
 */
type Method = "GET" | "POST" | "PUT" | "DELETE";

interface CsrfToken {
  headerName: string;
  token: string;
}

let csrf: CsrfToken | null = null;
let onSignedOut: () => void = () => {};
let onStepUpRequired: (needed: StepUpNeeded) => void = () => {};

export function setSignedOutHandler(handler: () => void): void {
  onSignedOut = handler;
}

/** The app shows its step-up explanation when a sensitive command is refused for want of a fresh sign-in. */
export function setStepUpRequiredHandler(handler: (needed: StepUpNeeded) => void): void {
  onStepUpRequired = handler;
}

async function safeJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

async function csrfToken(): Promise<CsrfToken> {
  if (csrf) return csrf;
  const response = await fetch("/api/csrf", { credentials: "same-origin", headers: { Accept: "application/json" } });
  if (!response.ok) throw new ApiError(problemFrom(response.status, await safeJson(response)));
  const body = (await response.json()) as { headerName: string; token: string };
  csrf = { headerName: body.headerName, token: body.token };
  return csrf;
}

/**
 * Turns a non-2xx response into an ApiError. A 401 also tells the app the session is gone; a 403 may mean the CSRF
 * token is stale or a fresh sign-in is needed first, and the app is told about the second.
 */
async function rejection(response: Response, body: unknown): Promise<ApiError> {
  if (response.status === 401) {
    csrf = null;
    onSignedOut();
  }
  const problem = problemFrom(response.status, await safeJson(response));
  if (response.status === 403) {
    if (problem.type.endsWith("/csrf-rejected")) csrf = null;
    const needed = stepUpNeededFrom(problem, body);
    if (needed) onStepUpRequired(needed);
  }
  return new ApiError(problem);
}

export async function request<T>(method: Method, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  const init: RequestInit = { method, credentials: "same-origin", headers };
  if (method !== "GET") {
    const token = await csrfToken();
    headers[token.headerName] = token.token;
  }
  if (body instanceof FormData) {
    init.body = body;
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const response = await fetch(path, init);
  if (!response.ok) throw await rejection(response, body);
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/** GET a file. Errors arrive as ApiError, exactly as for `request`. */
export async function download(path: string, fallbackName: string): Promise<Download> {
  const response = await fetch(path, { method: "GET", credentials: "same-origin", headers: { Accept: "*/*" } });
  if (!response.ok) throw await rejection(response, undefined);
  const named = /filename="([^"\\]+)"/.exec(response.headers.get("Content-Disposition") ?? "");
  return { content: await response.blob(), fileName: named?.[1] ?? fallbackName };
}
