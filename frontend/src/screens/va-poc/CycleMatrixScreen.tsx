import { useCallback } from "react";
import { dayLabel } from "../../api/cycles.ts";
import {
  fetchCycleMatrix,
  type MatrixSystem,
  type Milestone,
  type QuarterPhase,
  type QuarterStrip,
  type SystemState,
} from "../../api/dashboards.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { useSelectedCycle } from "../../app/CycleChoice.tsx";
import { HandledOutsideSection } from "../shared/HandledOutsideSection.tsx";
import { LapsedFlag } from "../shared/LapsedFlag.tsx";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { CloseCycleSection } from "./CloseCyclePanel.tsx";

const PHASE_LABELS: Readonly<Record<QuarterPhase, string>> = {
  HANDLED_OUTSIDE_APP: "handled outside the app",
  CLOSED: "closed",
  CURRENT: "current",
  UPCOMING: "upcoming",
};

const STATES = Object.keys(STATE_LABELS) as SystemState[];

function StripCard({ strip, cycle }: { strip: QuarterStrip; cycle: number }) {
  const counted = STATES.filter((state) => (strip.counts[state] ?? 0) > 0);
  return (
    <li className="board__card">
      <strong>
        Q{strip.quarter} {cycle}
      </strong>{" "}
      <span className="muted">{PHASE_LABELS[strip.phase]}</span>
      <br />
      <small className="muted">
        {dayLabel(strip.startsOn)} to {dayLabel(strip.endsOn)}
      </small>
      {strip.phase === "HANDLED_OUTSIDE_APP" ? null : (
        <ul className="plain-list">
          {counted.map((state) => (
            <li key={state}>
              {STATE_LABELS[state]}: {strip.counts[state]}
            </li>
          ))}
          {strip.endorsedLate > 0 ? <li>Endorsed Late: {strip.endorsedLate}</li> : null}
          <li className="muted">{strip.total} Systems</li>
        </ul>
      )}
    </li>
  );
}

/** One milestone: the day it was reached, or the day it is due, flagged when the state waits on it and it is Overdue. */
function MilestoneCell({ milestone, overdue, late }: { milestone: Milestone; overdue: boolean; late?: boolean }) {
  return (
    <td>
      {milestone.doneOn ? <>Done {dayLabel(milestone.doneOn)}</> : <span className="muted">Not yet</span>}
      {late ? (
        <>
          {" "}
          <span className="flag flag--warning">Endorsed Late</span>
        </>
      ) : null}
      <br />
      <small className={milestone.current ? undefined : "muted"}>
        {milestone.current ? "Waiting, due " : "Due "}
        {dayLabel(milestone.dueOn)}
      </small>
      {milestone.current && overdue ? (
        <>
          {" "}
          <span className="flag flag--danger">Overdue</span>
        </>
      ) : null}
    </td>
  );
}

function MatrixRow({ system }: { system: MatrixSystem }) {
  return (
    <tr>
      <td>
        <span className="mono">{system.systemId}</span> {system.name}
        <LapsedFlag lapsed={system.exemptionLapsed} />
      </td>
      <td>Q{system.quarter}</td>
      <MilestoneCell milestone={system.submitted} overdue={system.overdue} />
      <MilestoneCell milestone={system.reviewed} overdue={system.overdue} />
      <MilestoneCell milestone={system.endorsed} overdue={system.overdue} late={system.endorsedLate} />
      <td>{STATE_LABELS[system.state]}</td>
    </tr>
  );
}

/** One row per System of the Cycle with its milestones, or a note that the app runs none. */
function MatrixTable({ systems }: { systems: MatrixSystem[] }) {
  if (systems.length === 0) return <p className="muted">No Systems in Quarters the app runs.</p>;
  return (
    <div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">Systems of the VA Cycle and their milestones</caption>
        <thead>
          <tr>
            <th scope="col">System</th>
            <th scope="col">Quarter</th>
            <th scope="col">Submitted (due week 2)</th>
            <th scope="col">Reviewed (due week 4)</th>
            <th scope="col">Endorsed (due the Quarter&apos;s last day)</th>
            <th scope="col">State</th>
          </tr>
        </thead>
        <tbody>
          {systems.map((system) => (
            <MatrixRow key={system.id} system={system} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The Cycle matrix (story 64): the whole VA Cycle at once, a strip of its four Quarters with the count of Systems in each
 * state, then one row per System with its milestones against the day each is due. Every count and date is the server's.
 * Once the shown Cycle's Q4 has ended and until it closes, a VA POC may close it early (stories 17 and 68); the matrix
 * and the shell's list of Cycles are read again after the close.
 */
export function CycleMatrixScreen() {
  const cycle = useSelectedCycle();
  const load = useCallback(() => fetchCycleMatrix(cycle), [cycle]);
  const matrix = useResource(load);
  const shown = matrix.data;

  return (
    <div className="card">
      <h2>Cycle matrix</h2>
      <p className="muted">
        {shown ? `VA Cycle ${shown.cycle}: ` : ""}every Quarter, then every System with its milestones. Withdrawn
        submissions and Quarters handled outside the app are not counted.
      </p>
      {shown ? <CloseCycleSection year={shown.cycle} onClosed={matrix.reload} /> : null}
      {matrix.error ? <ErrorPanel problem={matrix.error.problem} /> : null}
      {matrix.loading && !shown ? <p className="muted">Loading the matrix…</p> : null}
      {shown ? (
        <>
          <ul className="plain-list quarter-strip" aria-label="Quarters of the VA Cycle">
            {shown.quarters.map((strip) => (
              <StripCard key={strip.quarter} strip={strip} cycle={shown.cycle} />
            ))}
          </ul>
          <MatrixTable systems={shown.systems} />
          <HandledOutsideSection id="matrix-outside" systems={shown.handledOutsideApp} />
        </>
      ) : null}
    </div>
  );
}
