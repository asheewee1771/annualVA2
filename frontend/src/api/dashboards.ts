import { request } from "./client.ts";
import type { SubmissionState } from "./submissions.ts";

/**
 * How a System in a Quarter is shown: Not Started while it has no VA Submission, otherwise its VA Submission's state.
 * The board's columns come in the server's order.
 */
export type SystemState = "NOT_STARTED" | SubmissionState;

/** What the signed-in person should do next with a System. */
export type NextAction =
  "START_SUBMISSION" | "PREPARE_SUBMISSION" | "AWAIT_REVIEW" | "AWAIT_ENDORSEMENT" | "AWAIT_EXEMPTION_DECISION";

/** The heading the server puts a System under. */
export type InboxGroup = "NEEDS_YOUR_ACTION" | "WAITING_ON_OTHERS" | "DONE";

/** A System in a Quarter the app runs, as every inbox lists it. Dates are business dates (YYYY-MM-DD, Asia/Singapore). */
interface DashboardSystem {
  id: string;
  /** The System ID as the VA POC typed it in the template. */
  systemId: string;
  name: string;
  quarter: number;
  state: SystemState;
  /** The VA Submission once it is started, otherwise null. */
  submissionId: string | null;
  submitDue: string;
  reviewDue: string;
  endorseDue: string;
  group: InboxGroup;
  /** The Overdue flag the server's sweep set: its window passed without the next step. Nothing locks. */
  overdue: boolean;
  /** A conditional Exemption of it lapsed: its scheduled VA was not recorded in time. */
  exemptionLapsed: boolean;
}

/** One of the SMO/Tech Lead's own Systems. */
export interface InboxSystem extends DashboardSystem {
  /** What is next; null once the VA Submission is in a final state. */
  nextAction: NextAction | null;
  /** The last day of the window the state waits on; null when no window runs (frozen, or final). */
  nextActionDueOn: string | null;
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

/** The query naming a VA Cycle and, for the board, a Quarter; omitted values are the ones running today. */
function query(params: Record<string, number | undefined>): string {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined) search.set(name, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

/** The SMO/Tech Lead's inbox for a VA Cycle (the one running today when no year is given). */
export function fetchSmoInbox(cycle?: number): Promise<SmoInbox> {
  return request<SmoInbox>("GET", `/api/dashboards/smo-inbox${query({ cycle })}`);
}

/** How far the Cyber Manager's review of a VA Submission's Findings is, and whether they may review it. */
interface ReviewMarkers {
  /** The Findings whose current proposal the Cyber Manager confirmed. */
  confirmed: number;
  /** The Findings whose current proposal awaits the Cyber Manager's review. */
  awaitingReview: number;
  /**
   * Segregation of duties refuses the caller's review: they acted on this VA Submission at another step, or are assigned to
   * another step of its System (its SMO/Tech Lead, say).
   */
  sodConflict: boolean;
}

/** One of the Systems assigned to the Cyber Manager, in a Quarter the app runs. */
export interface CmInboxSystem extends DashboardSystem, ReviewMarkers {
  /** The last day of the window the state waits on; null for a final or frozen state. */
  dueOn: string | null;
}

/** GET /api/dashboards/cm-inbox: the Cyber Manager's Systems for one VA Cycle, Overdue first, then by due date. */
export interface CmInbox {
  cycle: number;
  systems: CmInboxSystem[];
  handledOutsideApp: HandledOutsideSystem[];
}

/** The Cyber Manager's inbox for a VA Cycle (the one running today when no year is given). */
export function fetchCmInbox(cycle?: number): Promise<CmInbox> {
  return request<CmInbox>("GET", `/api/dashboards/cm-inbox${query({ cycle })}`);
}

/** One System on the Quarter board. */
export interface BoardCard extends ReviewMarkers {
  id: string;
  systemId: string;
  name: string;
  submissionId: string | null;
  overdue: boolean;
  /** A conditional Exemption of it lapsed. */
  exemptionLapsed: boolean;
  /** On a VA POC's board, the day the System was flagged SMO Unreachable; null while it is not, and for a Cyber Manager. */
  smoUnreachableOn: string | null;
}

/** GET /api/dashboards/quarter-board: one Quarter, one column per state in the server's order. */
export interface QuarterBoard {
  cycle: number;
  quarter: number;
  columns: { state: SystemState; systems: BoardCard[] }[];
  handledOutsideApp: HandledOutsideSystem[];
}

/** Where a Quarter stands today: run by the manual process, over, running, or still to come. */
export type QuarterPhase = "HANDLED_OUTSIDE_APP" | "CLOSED" | "CURRENT" | "UPCOMING";

/** One Quarter of the Cycle matrix's strip. Withdrawn is never counted; a Quarter Handled outside the app counts nothing. */
export interface QuarterStrip {
  quarter: number;
  startsOn: string;
  endsOn: string;
  phase: QuarterPhase;
  /** The number of Systems in each state, every state but Withdrawn, zeros included. */
  counts: Partial<Record<SystemState, number>>;
  total: number;
  /** How many of the Endorsed were endorsed after the Quarter's last day. */
  endorsedLate: number;
}

/** One milestone of a System: submitted, reviewed (passed to endorsement) or endorsed. */
export interface Milestone {
  /** The last day of the window it is due in. */
  dueOn: string;
  /** The business date it was last reached; null until it is. */
  doneOn: string | null;
  /** The System's state waits on this milestone now; the row's Overdue flag says whether its window passed. */
  current: boolean;
}

/** One System of the Cycle, in a Quarter the app runs. */
export interface MatrixSystem {
  id: string;
  systemId: string;
  name: string;
  quarter: number;
  state: SystemState;
  submissionId: string | null;
  overdue: boolean;
  endorsedLate: boolean;
  /** A conditional Exemption of it lapsed. */
  exemptionLapsed: boolean;
  submitted: Milestone;
  reviewed: Milestone;
  endorsed: Milestone;
}

/** GET /api/dashboards/cycle-matrix: the whole VA Cycle for a VA POC, a strip of four Quarters then one row per System. */
export interface CycleMatrix {
  cycle: number;
  quarters: QuarterStrip[];
  systems: MatrixSystem[];
  handledOutsideApp: HandledOutsideSystem[];
}

/** The Cycle matrix (the VA Cycle running today when no year is given). */
export function fetchCycleMatrix(cycle?: number): Promise<CycleMatrix> {
  return request<CycleMatrix>("GET", `/api/dashboards/cycle-matrix${query({ cycle })}`);
}

/** The Quarter board (the VA Cycle and Quarter running today when none is given). */
export function fetchQuarterBoard(cycle?: number, quarter?: number): Promise<QuarterBoard> {
  return request<QuarterBoard>("GET", `/api/dashboards/quarter-board${query({ cycle, quarter })}`);
}
