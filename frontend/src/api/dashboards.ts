import { request } from "./client.ts";

/** How a System in a Quarter is shown; only Not Started exists until a VA Submission can be started. */
export type InboxState = "NOT_STARTED";

/** What the signed-in person should do next with a System. */
export type NextAction = "START_SUBMISSION";

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
  submitDue: string;
  reviewDue: string;
  endorseDue: string;
  nextAction: NextAction;
  nextActionDueOn: string;
  group: InboxGroup;
  /** Set by the server once the Overdue flag exists; the screen shows the pill when it is true. */
  overdue?: boolean;
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
