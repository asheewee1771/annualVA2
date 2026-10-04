import { ApiError, problemFrom } from "./problem.ts";

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

export function setSignedOutHandler(handler: () => void): void {
  onSignedOut = handler;
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
  if (response.status === 401) {
    csrf = null;
    onSignedOut();
  }
  if (response.status === 403) {
    const problem = problemFrom(403, await safeJson(response));
    if (problem.type.endsWith("/csrf-rejected")) csrf = null;
    throw new ApiError(problem);
  }
  if (!response.ok) {
    throw new ApiError(problemFrom(response.status, await safeJson(response)));
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
