import { request } from "./client.ts";

/** GET /api/session: whether a session exists and what the shell must show. */
export interface Session {
  authenticated: boolean;
  classification: string;
  uuid?: string;
  name?: string;
  email?: string;
  roles?: string[];
  idleTimeoutSeconds?: number;
  absoluteExpiresAt?: string;
}

export function fetchSession(): Promise<Session> {
  return request<Session>("GET", "/api/session");
}

/** POST /api/logout (CSRF-protected): ends the server-side session; the answer is 204. */
export function signOut(): Promise<void> {
  return request<void>("POST", "/api/logout");
}
