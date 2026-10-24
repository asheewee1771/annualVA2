import { summarise } from "../api/cycles.ts";
import { useCycleChoice } from "./CycleChoice.tsx";

/**
 * The header's answer to "where are we in the year": VA Cycle, Quarter, week number and the current window, from
 * GET /api/cycles as the shell read it. A Quarter the manual process still runs says so. It is for people who hold a role;
 * a failure to load it says so in the header and never blocks the screen.
 */
export function CycleStatus() {
  const cycles = useCycleChoice()?.cycles;

  if (!cycles || cycles.error) {
    return <p className="cycle-status muted">The calendar could not be loaded.</p>;
  }
  if (!cycles.data) {
    return <p className="cycle-status muted">Loading the calendar…</p>;
  }
  const summary = summarise(cycles.data);
  return (
    <p className="cycle-status" aria-label="Current VA Cycle">
      <strong>{summary.position}</strong>
      {summary.window ? <span> · {summary.window}</span> : null}
      {summary.handledOutsideApp ? <span className="cycle-status__flag"> · Handled outside the app</span> : null}
      {summary.closing ? <span className="muted"> · {summary.closing}</span> : null}
    </p>
  );
}
