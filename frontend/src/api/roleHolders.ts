import { request } from "./client.ts";

export type HolderRole = "VA_POC" | "CYBER_MANAGER" | "SMO" | "AUDITOR";
export type HolderState = "ACTIVE" | "LAPSED" | "SUSPENDED" | "EXPIRED";
type SuspensionCause = "REVIEW" | "IDENTITY_PROVIDER";
export type ReturnTrigger =
  "REGISTER" | "QUARTER_START" | "RETURN_FOR_REWORK" | "SUBMITTED_FOR_REVIEW" | "EXEMPTION_LAPSE" | "CONFIGURATION";

/** One role holder against the System Register (ticket 47). Emails and names are data: shown only as text. */
export interface RoleHolder {
  /** The grant's id. */
  id: string;
  role: HolderRole;
  email: string;
  displayName: string | null;
  systemId: string | null;
  systemCode: string | null;
  systemName: string | null;
  source: "CONFIG" | "REGISTER" | "GRANT";
  state: HolderState;
  grantedAt: string;
  expiresAt: string | null;
  lastUsedAt: string | null;
  lapsedAt: string | null;
  suspendedAt: string | null;
  suspensionCause: SuspensionCause | null;
  returnedAt: string | null;
  returnTrigger: ReturnTrigger | null;
  /** Whether the Register names this person for this role on this System now; null for a role it does not give. */
  namedInRegister: boolean | null;
  signedIn: boolean;
  disabledAtIdentityProvider: boolean;
  /** The caller's own grant, which they may not suspend or restore. */
  own: boolean;
  /** Whether the caller may suspend or restore it, by the rules the server refuses by. */
  suspendable: boolean;
  restorable: boolean;
}

interface CompletedReview {
  reviewedAt: string;
  reviewedBy: string;
  /** How many role holders the review checked: the length of grantIds. */
  holders: number;
  /** The grants the VA POC checked, as the screen listed them (ADR 0056 item 4). */
  grantIds: string[];
}

export type DueReason = "never-reviewed" | "three-months" | "register-applied";

interface ReviewView {
  holders: RoleHolder[];
  lastReview: CompletedReview | null;
  reviewDue: boolean;
  dueBecause: DueReason[];
  /** The roles that came back by themselves since the last review, newest first. */
  automaticReturns: RoleHolder[];
}

const HOLDERS = "/api/role-holders";

/** GET /api/role-holders (VA POC only). */
export function fetchRoleHolderReview(): Promise<ReviewView> {
  return request<ReviewView>("GET", HOLDERS);
}

/** POST /api/role-holders/reviews: records that the caller has completed a review of the holders the screen listed. */
export function completeRoleHolderReview(grantIds: string[]): Promise<CompletedReview> {
  return request<CompletedReview>("POST", `${HOLDERS}/reviews`, { grantIds });
}

/** POST /api/role-holders/{grantId}/suspend, a step-up action. */
export function suspendRole(grantId: string): Promise<RoleHolder> {
  return request<RoleHolder>("POST", `${HOLDERS}/${encodeURIComponent(grantId)}/suspend`, {});
}

/** POST /api/role-holders/{grantId}/restore, a step-up action. */
export function restoreRole(grantId: string): Promise<RoleHolder> {
  return request<RoleHolder>("POST", `${HOLDERS}/${encodeURIComponent(grantId)}/restore`, {});
}
