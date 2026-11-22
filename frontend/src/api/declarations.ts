import { request } from "./client.ts";

/** The most characters a Declaration wording may hold once the server has cleaned it. */
export const MAX_DECLARATION_LENGTH = 4000;

/** One version of the Declaration wording as a VA POC reads the list of them (ticket 61). */
export interface DeclarationVersion {
  version: number;
  /** The wording, shown only as text. */
  text: string;
  /** True for the placeholder seeded with the app. */
  placeholder: boolean;
  /** The VA POC who published it; null for the placeholder. */
  publishedBy: string | null;
  publishedByName: string | null;
  /** When it was published and so became current; null for the placeholder. */
  publishedAt: string | null;
  /** Whether the SMO/Tech Leads accept it now; every other version is superseded. */
  current: boolean;
  /** How many VA Submissions accepted it at a submit. */
  submissionCount: number;
}

const VERSIONS = "/api/declaration-text/versions";

/** GET /api/declaration-text/versions: every version, newest first (VA POC only). */
export function fetchDeclarationVersions(): Promise<DeclarationVersion[]> {
  return request<DeclarationVersion[]>("GET", VERSIONS);
}

/**
 * POST /api/declaration-text/versions, a step-up action: the wording becomes the next version, current at once. The
 * server cleans it and answers 400 `invalid-field` for a wording missing or too long.
 */
export function publishDeclarationVersion(text: string): Promise<DeclarationVersion> {
  return request<DeclarationVersion>("POST", VERSIONS, { text });
}
