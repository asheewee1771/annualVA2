import { request } from "./client.ts";

/** The windows of a Quarter, as the server names them. */
export type WindowName = "SUBMISSION" | "REVIEW" | "ENDORSEMENT";

export type CyclePhase = "UPCOMING" | "RUNNING" | "CLOSING" | "CLOSED";

/** Today's place in a VA Cycle (GET /api/cycles); null when today is outside the Cycle's four Quarters. */
export interface CurrentPosition {
  quarter: number;
  /** From 1; a 14-week Quarter has a week 14. */
  week: number;
  window: WindowName;
  /** The last day of the current window, a business date (YYYY-MM-DD). */
  windowEndsOn: string;
  /** True for a Quarter before the first live Quarter: the manual process still runs it. */
  handledOutsideApp: boolean;
}

/** One VA Cycle as the server computes it; every date is a business date in Asia/Singapore (YYYY-MM-DD). */
export interface Cycle {
  year: number;
  startsOn: string;
  endsOn: string;
  /** Its last open day: Q4's end plus 28 days, or the day a VA POC closed it early. */
  closesOn: string;
  phase: CyclePhase;
  /** Open from its Q1 through its close; while two are open the shell offers a Cycle selector. */
  open: boolean;
  /** When its close was recorded (an instant, UTC); null until it is. */
  closedAt: string | null;
  current: CurrentPosition | null;
}

export interface CycleList {
  businessDate: string;
  /** The year of the Cycle whose Quarters today falls in; null before the first live Cycle starts. */
  currentCycle: number | null;
  cycles: Cycle[];
}

export function fetchCycles(): Promise<CycleList> {
  return request<CycleList>("GET", "/api/cycles");
}

/** What a close answers: the Cycle's last open day, when it closed and how many it made Not Endorsed. */
export interface ClosedCycle {
  year: number;
  closesOn: string;
  closedAt: string;
  notEndorsed: number;
}

/**
 * POST /api/cycles/{year}/close: a VA POC closes the VA Cycle now, after a fresh sign-in. Every VA Submission that is
 * neither Endorsed nor Exempted, and every System with none, becomes Not Endorsed. The server answers 409 while Q4 is
 * still running or once the Cycle is closed.
 */
export function closeCycle(year: number): Promise<ClosedCycle> {
  return request<ClosedCycle>("POST", `/api/cycles/${encodeURIComponent(String(year))}/close`, {});
}

const WINDOW_LABELS: Readonly<Record<WindowName, string>> = {
  SUBMISSION: "Submission Window",
  REVIEW: "Review Window",
  ENDORSEMENT: "Endorsement Window",
};

/** Business dates arrive as plain dates; they are shown as dates and never shifted by the browser's own zone. */
const DAY = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeZone: "UTC" });

export function dayLabel(date: string): string {
  const parsed = new Date(`${date}T00:00:00Z`);
  return Number.isNaN(parsed.getTime()) ? date : DAY.format(parsed);
}

/** What the header says: the Cycle, Quarter, week and window, or what is coming when no Cycle is running yet. */
export interface CycleSummary {
  /** For example "VA Cycle 2027 · Q3 · Week 1". */
  position: string;
  /** For example "Submission Window until 15 Aug 2027"; null when there is no current window. */
  window: string | null;
  /** True for a Quarter the manual process still runs. */
  handledOutsideApp: boolean;
  /** A Cycle still open after the current one began, for example "VA Cycle 2027 closes 5 Mar 2028"; null otherwise. */
  closing: string | null;
}

export function summarise(list: CycleList): CycleSummary {
  const running = list.cycles.find((cycle) => cycle.year === list.currentCycle);
  const closing = list.cycles.find((cycle) => cycle.phase === "CLOSING");
  const closingNote = closing ? `VA Cycle ${closing.year} closes ${dayLabel(closing.closesOn)}` : null;
  if (!running?.current) {
    const upcoming = list.cycles.find((cycle) => cycle.phase === "UPCOMING");
    return {
      position: upcoming ? `VA Cycle ${upcoming.year} starts ${dayLabel(upcoming.startsOn)}` : "No VA Cycle is running",
      window: null,
      handledOutsideApp: false,
      closing: closingNote,
    };
  }
  const { quarter, week, window, windowEndsOn, handledOutsideApp } = running.current;
  return {
    position: `VA Cycle ${running.year} · Q${quarter} · Week ${week}`,
    window: `${WINDOW_LABELS[window]} until ${dayLabel(windowEndsOn)}`,
    handledOutsideApp,
    closing: closingNote,
  };
}
