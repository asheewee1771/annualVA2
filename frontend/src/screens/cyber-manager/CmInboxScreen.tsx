import { useCallback, useState } from "react";
import { dayLabel } from "../../api/cycles.ts";
import { fetchCmInbox, type CmInboxSystem, type InboxGroup } from "../../api/dashboards.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { CycleSelector } from "../shared/CycleSelector.tsx";
import { HandledOutsideSection } from "../shared/HandledOutsideSection.tsx";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { reviewPath } from "./paths.ts";
import { findingsLabel, ReviewFlags } from "./ReviewFlags.tsx";

const GROUPS: readonly { group: InboxGroup; title: string; empty: string }[] = [
  { group: "NEEDS_YOUR_ACTION", title: "Needs your review", empty: "Nothing is waiting for your review." },
  { group: "WAITING_ON_OTHERS", title: "Waiting on others", empty: "Nothing is waiting on anyone else." },
  { group: "DONE", title: "Done", empty: "Nothing is done yet." },
];

function InboxRow({ system }: { system: CmInboxSystem }) {
  return (
    <tr>
      <td>
        <span className="mono">{system.systemId}</span> {system.name}
        <ReviewFlags overdue={system.overdue} sodConflict={system.sodConflict} />
        {system.submissionId ? (
          <>
            <br />
            <AppLink path={reviewPath(system.submissionId)}>
              {system.state === "IN_REVIEW" ? "Review" : "Open"}
              <span className="visually-hidden"> {system.systemId}</span>
            </AppLink>
          </>
        ) : null}
      </td>
      <td>Q{system.quarter}</td>
      <td>{STATE_LABELS[system.state]}</td>
      <td>{system.dueOn ? dayLabel(system.dueOn) : "No window"}</td>
      <td>{findingsLabel(system)}</td>
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
  systems: CmInboxSystem[];
}) {
  return (
    <section aria-labelledby={`cm-inbox-${group}`}>
      <h3 id={`cm-inbox-${group}`}>
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
                <th scope="col">Due by</th>
                <th scope="col">Findings</th>
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

/**
 * The Cyber Manager's action inbox (story 48): the Systems assigned to them for the VA Cycle, Overdue first and then by
 * the day the current window ends, under the heading the server decides, with how far the review of the Findings is.
 * The order, the headings and the segregation-of-duties marker are the server's answers; this only shows them.
 */
export function CmInboxScreen({ classification }: { classification: string }) {
  const [cycle, setCycle] = useState<number | undefined>(undefined);
  const load = useCallback(() => fetchCmInbox(cycle), [cycle]);
  const inbox = useResource(load);

  return (
    <div className="card">
      <h2>Your Systems to review</h2>
      <p className="muted">
        The VA Submissions of the Systems assigned to you, Overdue first. The windows are soft: nothing locks when one
        passes.
      </p>
      <CycleSelector
        id="cm-inbox-cycle"
        classification={classification}
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
      {inbox.data ? <HandledOutsideSection id="cm-inbox-outside" systems={inbox.data.handledOutsideApp} /> : null}
    </div>
  );
}
