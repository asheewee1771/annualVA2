import { useCallback, useState } from "react";
import { fetchQuarterBoard, type BoardCard, type SystemState } from "../../api/dashboards.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { CycleSelector } from "./CycleSelector.tsx";
import { HandledOutsideSection } from "./HandledOutsideSection.tsx";
import { findingsLabel, ReviewFlags } from "./ReviewFlags.tsx";
import { STATE_LABELS } from "./systemStates.ts";

const QUARTERS = [1, 2, 3, 4] as const;

function Column({ state, systems }: { state: SystemState; systems: BoardCard[] }) {
  return (
    <section className="board__column" aria-labelledby={`board-${state}`}>
      <h3 id={`board-${state}`}>
        {STATE_LABELS[state]} ({systems.length})
      </h3>
      <ul className="plain-list">
        {systems.map((system) => (
          <li key={system.id} className="board__card">
            <span className="mono">{system.systemId}</span> {system.name}
            <ReviewFlags overdue={system.overdue} sodConflict={system.sodConflict} />
            <br />
            <small className="muted">{findingsLabel(system)}</small>
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * The Quarter board (stories 49 and 64): one Quarter at once, its Systems in one column per state, in the order the server
 * gives. The server decides whose Systems they are: a Cyber Manager's own, or every System for a VA POC. The Quarter
 * running today is shown first; another can be chosen.
 *
 * @param whose how the subtitle names the Systems shown, such as "your Systems"
 */
export function QuarterBoardScreen({ classification, whose }: { classification: string; whose: string }) {
  const [cycle, setCycle] = useState<number | undefined>(undefined);
  const [quarter, setQuarter] = useState<number | undefined>(undefined);
  const load = useCallback(() => fetchQuarterBoard(cycle, quarter), [cycle, quarter]);
  const board = useResource(load);
  const shown = board.data;

  return (
    <div className="card">
      <h2>Quarter board</h2>
      <p className="muted">
        {shown ? `Q${shown.quarter} of VA Cycle ${shown.cycle}: ` : ""}
        {whose} in one column per state.
      </p>
      <CycleSelector
        id="board-cycle"
        classification={classification}
        selected={cycle ?? shown?.cycle}
        onSelect={setCycle}
      />
      <ClassifiedField label="Quarter" classification={classification} id="board-quarter">
        <select
          id="board-quarter"
          value={quarter ?? shown?.quarter ?? ""}
          onChange={(event) => setQuarter(Number(event.target.value))}
        >
          {QUARTERS.map((number) => (
            <option key={number} value={number}>
              Q{number}
            </option>
          ))}
        </select>
      </ClassifiedField>
      {board.error ? <ErrorPanel problem={board.error.problem} /> : null}
      {board.loading && !shown ? <p className="muted">Loading the board…</p> : null}
      {shown ? (
        <div className="board" role="region" aria-label="Quarter board columns" tabIndex={0}>
          {shown.columns.map((column) => (
            <Column key={column.state} state={column.state} systems={column.systems} />
          ))}
        </div>
      ) : null}
      {shown ? <HandledOutsideSection id="board-outside" systems={shown.handledOutsideApp} /> : null}
    </div>
  );
}
