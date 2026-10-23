import { request } from "./client.ts";

/** How a System in a Quarter is shown; the other states arrive with the commands that enter them. */
export type InboxState = "NOT_STARTED" | "DRAFT" | "IN_REVIEW" | "RETURNED_FOR_REWORK";

/** What the signed-in person should do next with a System. */
export type NextAction = "START_SUBMISSION" | "PREPARE_SUBMISSION" | "AWAIT_REVIEW";

/** The heading the server puts a System under. */
export type InboxGroup = "NEEDS_YOUR_ACTION" | "WAITING_ON_OTHERS" | "DONE";

/** One of the caller's Systems in a Quarter the app runs. Dates are business dates (YYYY-MM-DD, Asia/Singapore). */
export interface InboxSystem {
  id: string;
  /** The System ID as the VA POC typed it in the template. */
  systemId: string;
  name: string;
  quarter: number;
  state: InboxState;
  /** The VA Submission once it is started, otherwise null. */
  submissionId: string | null;
  submitDue: string;
  reviewDue: string;
  endorseDue: string;
  nextAction: NextAction;
  nextActionDueOn: string;
  group: InboxGroup;
  /** The Overdue flag the server's sweep set: its window passed without the next step. Nothing locks. */
  overdue: boolean;
}

/** A System in a Quarter the manual process still runs: named only, nothing to do in the app. */
export interface HandledOutsideSystem {
  id: string;
  systemId: string;
  name: string;
  quarter: number;
}

/** GET /api/dashboards/smo-inbox: the SMO/Tech Lead's own Systems for one VA Cycle. */
export interface SmoInbox {
  cycle: number;
  systems: InboxSystem[];
  handledOutsideApp: HandledOutsideSystem[];
}

/** The SMO/Tech Lead's inbox for a VA Cycle (the one running today when no year is given). */
export function fetchSmoInbox(cycle?: number): Promise<SmoInbox> {
  const query = cycle === undefined ? "" : `?cycle=${encodeURIComponent(String(cycle))}`;
  return request<SmoInbox>("GET", `/api/dashboards/smo-inbox${query}`);
}
