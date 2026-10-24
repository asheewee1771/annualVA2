import { useState } from "react";
import {
  approveDisposalList,
  fetchDisposalLists,
  type DisposalItem,
  type DisposalItemKind,
  type DisposalList,
  type DisposalReason,
} from "../../api/disposal.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { isStepUpRequired } from "../../api/stepUp.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";

/** Times as the rest of the app shows them, in Singapore time. */
const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });
const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

const KIND_LABELS: Readonly<Record<DisposalItemKind, string>> = {
  SUBMISSION: "VA Submission and everything in it",
  FILE: "Scan Report file",
  SUPERSEDED_FILE: "Content of a replaced file (its record is kept)",
  EXPORT: "Expired export",
  SYSTEM: "Retired System",
  CYCLE: "Records of the VA Cycle about no one VA Submission",
  AUDIT_RANGE: "Audit log entries",
};

const REASON_LABELS: Readonly<Record<DisposalReason, string>> = {
  CYCLE_RETENTION_ENDED: "The VA Cycle closed more than 3 years ago",
  SUPERSEDED_AFTER_ENDORSEMENT: "Replaced by a rescan, and the VA Submission is Endorsed",
  EXPORT_EXPIRED: "Its 24 hours are up",
  SYSTEM_RETIRED: "Retired, and nothing of its VA Cycles is kept",
};

const STATUS_LABELS: Readonly<Record<DisposalList["status"], string>> = {
  DRAFT: "Waiting for approval",
  SUPERSEDED: "Replaced by a newer list",
  APPROVED: "Approved: being disposed of",
  EXECUTED: "Disposed of",
};

function problemOf(failure: unknown): Problem {
  return failure instanceof ApiError ? failure.problem : UNREACHABLE;
}

/** What the item is about, in words: the System, the Cycle, or the number of entries. */
function about(item: DisposalItem): string {
  const parts: string[] = [];
  if (item.systemCode !== null) parts.push(`${item.systemCode} ${item.systemName ?? ""}`.trim());
  if (item.cycle !== null) parts.push(`VA Cycle ${item.cycle}`);
  if (item.entries !== null) parts.push(`${item.entries} entries`);
  return parts.join(", ");
}

/** How many items of each kind, in the order the kinds are listed above. */
function counts(list: DisposalList): string {
  return (Object.keys(KIND_LABELS) as DisposalItemKind[])
    .map((kind) => [kind, list.items.filter((item) => item.kind === kind).length] as const)
    .filter(([, count]) => count > 0)
    .map(([kind, count]) => `${KIND_LABELS[kind]}: ${count}`)
    .join("; ");
}

function ItemsTable({ list }: { list: DisposalList }) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">What this list disposes of, in the order it was listed</caption>
        <thead>
          <tr>
            <th scope="col">What</th>
            <th scope="col">About</th>
            <th scope="col">Why it is due</th>
            <th scope="col">Id</th>
          </tr>
        </thead>
        <tbody>
          {list.items.map((item, index) => (
            <tr key={`${item.kind}-${item.targetId ?? index}`}>
              <td>{KIND_LABELS[item.kind]}</td>
              <td>{about(item)}</td>
              <td>{REASON_LABELS[item.reason]}</td>
              <td>
                <small className="muted">{item.targetId ?? ""}</small>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** The list waiting for a VA POC, with Approve; or the approved list waiting for the executor. */
function WaitingList({ list, onApproved }: { list: DisposalList; onApproved: () => void }) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function approve() {
    setBusy(true);
    setProblem(undefined);
    try {
      await approveDisposalList(list.id);
      onApproved();
    } catch (failure) {
      const refused = problemOf(failure);
      // The app already explains a step-up and brings the VA POC back here, to press Approve again.
      if (!isStepUpRequired(refused)) setProblem(refused);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card" aria-labelledby="waiting-title">
      <h3 id="waiting-title">{STATUS_LABELS[list.status]}</h3>
      <p className="muted">
        Listed on {list.computedOn}. {counts(list)}.
        {list.cutoff === null ? null : (
          <> Records written before {WHEN.format(new Date(list.cutoff))} are disposed of.</>
        )}
      </p>
      <ItemsTable list={list} />
      {problem ? <ErrorPanel problem={problem} /> : null}
      {list.status === "DRAFT" ? (
        <p>
          <button type="button" className="button" disabled={busy} onClick={() => void approve()}>
            Approve the disposal
          </button>{" "}
          <small className="muted">
            Deletion cannot be undone. You need to have signed in within the last five minutes.
          </small>
        </p>
      ) : null}
    </section>
  );
}

/**
 * The VA POC's Disposal screen (story 87): the records due for disposal after their retention period, listed daily by
 * the retention job, for a VA POC to review and approve with a fresh sign-in; and the lists disposed of before. The
 * server decides what is due and refuses a list that has been replaced since it was read.
 */
export function DisposalScreen() {
  const lists = useResource(fetchDisposalLists);
  const waiting = (lists.data ?? []).find((list) => list.status === "DRAFT" || list.status === "APPROVED");
  const executed = (lists.data ?? []).filter((list) => list.status === "EXECUTED");

  return (
    <div className="card">
      <h2>Disposal</h2>
      <p className="muted">
        Records are kept for the VA Cycle plus 3 years; a replaced file&apos;s content until its VA Submission is
        Endorsed; an export for 24 hours. Then they are listed here, and deleted only once a VA POC approves the list.
      </p>
      {lists.error ? <ErrorPanel problem={lists.error.problem} /> : null}
      {lists.data && waiting === undefined ? <p className="muted">Nothing is due for disposal.</p> : null}
      {waiting ? <WaitingList list={waiting} onApproved={lists.reload} /> : null}
      {executed.length > 0 ? (
        <section className="card" aria-labelledby="executed-title">
          <h3 id="executed-title">Disposed of</h3>
          <ul className="plain-list">
            {executed.map((list) => (
              <li key={list.id}>
                {list.executedAt === null ? "" : WHEN.format(new Date(list.executedAt))}: {counts(list)}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
