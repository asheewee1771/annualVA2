import { useCallback, useState } from "react";
import { dayLabel, fetchCycles } from "../../api/cycles.ts";
import {
  fetchSmoInbox,
  type HandledOutsideSystem,
  type InboxGroup,
  type InboxState,
  type InboxSystem,
  type NextAction,
} from "../../api/dashboards.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";

const GROUPS: readonly { group: InboxGroup; title: string; empty: string }[] = [
  { group: "NEEDS_YOUR_ACTION", title: "Needs your action", empty: "Nothing needs your action." },
  { group: "WAITING_ON_OTHERS", title: "Waiting on others", empty: "Nothing is waiting on anyone else." },
  { group: "DONE", title: "Done", empty: "Nothing is done yet." },
];

const STATE_LABELS: Readonly<Record<InboxState, string>> = { NOT_STARTED: "Not Started" };

const ACTION_LABELS: Readonly<Record<NextAction, string>> = { START_SUBMISSION: "Start the VA Submission" };

/** The Start button of a Not Started System; the next ticket wires it to start the VA Submission. */
function StartButton({ system }: { system: InboxSystem }) {
  return (
    <button
      type="button"
      className="button"
      disabled
      aria-label={`Start the VA Submission for ${system.systemId}`}
      title="Starting a VA Submission is not available yet"
    >
      Start
    </button>
  );
}

function InboxRow({ system }: { system: InboxSystem }) {
  return (
    <tr>
      <td>
        <span className="mono">{system.systemId}</span> {system.name}
        {system.overdue ? (
          <>
            {" "}
            <span className="flag flag--danger">Overdue</span>
          </>
        ) : null}
      </td>
      <td>Q{system.quarter}</td>
      <td>{STATE_LABELS[system.state]}</td>
      <td>
        {ACTION_LABELS[system.nextAction]} by {dayLabel(system.nextActionDueOn)}
      </td>
      <td>{dayLabel(system.submitDue)}</td>
      <td>{dayLabel(system.reviewDue)}</td>
      <td>{dayLabel(system.endorseDue)}</td>
      <td>{system.state === "NOT_STARTED" ? <StartButton system={system} /> : null}</td>
    </tr>
  );
}

function InboxGroupSection({
  group,
  title,
  empty,
  systems,
}: {
  group: InboxGroup;
  title: string;
  empty: string;
  systems: InboxSystem[];
}) {
  return (
    <section aria-labelledby={`inbox-${group}`}>
      <h3 id={`inbox-${group}`}>
        {title} ({systems.length})
      </h3>
      {systems.length === 0 ? (
        <p className="muted">{empty}</p>
      ) : (
        <div className="table-scroll">
          <table className="data-table">
            <caption className="visually-hidden">{title}</caption>
            <thead>
              <tr>
                <th scope="col">System</th>
                <th scope="col">Quarter</th>
                <th scope="col">State</th>
                <th scope="col">Next</th>
                <th scope="col">Submit by</th>
                <th scope="col">Review by</th>
                <th scope="col">Endorse by</th>
                <th scope="col">
                  <span className="visually-hidden">Action</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {systems.map((system) => (
                <InboxRow key={system.id} system={system} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function HandledOutsideSection({ systems }: { systems: HandledOutsideSystem[] }) {
  if (systems.length === 0) return null;
  return (
    <section aria-labelledby="inbox-outside">
      <h3 id="inbox-outside">Handled outside the app ({systems.length})</h3>
      <p className="muted">These Quarters are run by the manual process. There is nothing to do here.</p>
      <div className="table-scroll">
        <table className="data-table">
          <caption className="visually-hidden">Handled outside the app</caption>
          <thead>
            <tr>
              <th scope="col">System</th>
              <th scope="col">Quarter</th>
            </tr>
          </thead>
          <tbody>
            {systems.map((system) => (
              <tr key={system.id}>
                <td>
                  <span className="mono">{system.systemId}</span> {system.name}
                </td>
                <td>Q{system.quarter}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** Shown only while two VA Cycles are open at once (the older one is still taking endorsements). */
function CycleSelector({
  classification,
  years,
  selected,
  onSelect,
}: {
  classification: string;
  years: number[];
  selected: number | undefined;
  onSelect: (year: number) => void;
}) {
  if (years.length < 2) return null;
  return (
    <ClassifiedField label="VA Cycle" classification={classification} id="inbox-cycle">
      <select id="inbox-cycle" value={selected ?? ""} onChange={(event) => onSelect(Number(event.target.value))}>
        {years.map((year) => (
          <option key={year} value={year}>
            VA Cycle {year}
          </option>
        ))}
      </select>
    </ClassifiedField>
  );
}

/**
 * The SMO/Tech Lead's inbox (stories 18 and 71): their Systems for the VA Cycle, grouped by what the server says each
 * needs, with the state, the three due dates and what to do next. Systems in Quarters the manual process runs are listed
 * apart. Which heading a System is under and what is next are the server's answers; this only shows them.
 */
export function SmoInboxScreen({ classification }: { classification: string }) {
  const [cycle, setCycle] = useState<number | undefined>(undefined);
  const load = useCallback(() => fetchSmoInbox(cycle), [cycle]);
  const inbox = useResource(load);
  const cycles = useResource(fetchCycles);
  const open = cycles.data?.cycles.filter((candidate) => candidate.phase !== "CLOSED").map((c) => c.year) ?? [];

  return (
    <div className="card">
      <h2>Your Systems</h2>
      <p className="muted">
        What you need to do for each of your Systems, and by when. The windows are soft: nothing locks when one passes.
      </p>
      <CycleSelector
        classification={classification}
        years={open}
        selected={cycle ?? inbox.data?.cycle}
        onSelect={setCycle}
      />
      {inbox.error ? <ErrorPanel problem={inbox.error.problem} /> : null}
      {inbox.loading && !inbox.data ? <p className="muted">Loading your Systems…</p> : null}
      {inbox.data
        ? GROUPS.map(({ group, title, empty }) => (
            <InboxGroupSection
              key={group}
              group={group}
              title={title}
              empty={empty}
              systems={inbox.data?.systems.filter((system) => system.group === group) ?? []}
            />
          ))
        : null}
      {inbox.data ? <HandledOutsideSection systems={inbox.data.handledOutsideApp} /> : null}
    </div>
  );
}
