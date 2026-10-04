import { request } from "./client.ts";

/** GET /api/session: whether a session exists and what the shell must show. */
export interface Session {
  authenticated: boolean;
  classification: string;
  uuid?: string;
  name?: string;
  email?: string;
  roles?: string[];
}

export function fetchSession(): Promise<Session> {
  return request<Session>("GET", "/api/session");
}
