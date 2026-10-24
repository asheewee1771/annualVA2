import type { ReactNode } from "react";
import type { PreviewRow, RegisterEffect, RegisterOutcome, RegisterPreview } from "../../api/systemRegister.ts";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { REASON_TEXT, changedFields } from "./registerPreviewText.ts";

/** Business times are shown in Singapore time, whatever the browser's own zone is. */
const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });

function when(instant: string): string {
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? instant : WHEN.format(date);
}

function rowKey(row: PreviewRow, index: number): string {
  return `${row.sheetRow ?? "left-out"}-${row.systemId ?? ""}-${index}`;
}

/** The SMO/Tech Lead's email, flagged when no sign-in has matched it yet (story 12). */
function Smo({ row, email }: { row: PreviewRow; email: string }) {
  return (
    <>
      {email}
      {row.unlinkedSmo ? (
        <>
          {" "}
          <span className="flag flag--warning">Unlinked SMO</span>
        </>
      ) : null}
    </>
  );
}

function Group({ title, note, count, children }: { title: string; note: string; count: number; children: ReactNode }) {
  if (count === 0) return null;
  return (
    <section className="preview-group" aria-label={title}>
      <h3>
        {title} ({count})
      </h3>
      <p className="muted">{note}</p>
      <div className="table-scroll">{children}</div>
    </section>
  );
}

/** The Systems a group lists with what the file says about each: those to add, and those already as the file says. */
function SystemTable({ rows, caption }: { rows: PreviewRow[]; caption: string }) {
  return (
    <table className="data-table">
      <caption className="visually-hidden">{caption}</caption>
      <thead>
        <tr>
          <th scope="col">Row</th>
          <th scope="col">System ID</th>
          <th scope="col">System Name</th>
          <th scope="col">SMO/Tech Lead</th>
          <th scope="col">Cyber Manager</th>
          <th scope="col">Quarter</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={rowKey(row, index)}>
            <td>{row.sheetRow}</td>
            <td className="mono">{row.systemId}</td>
            <td>{row.after?.name}</td>
            <td>{row.after ? <Smo row={row} email={row.after.smoEmail} /> : null}</td>
            <td>{row.after?.cyberManagerEmail}</td>
            <td>{row.after?.quarter}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function UpdateTable({ rows }: { rows: PreviewRow[] }) {
  return (
    <table className="data-table">
      <caption className="visually-hidden">Systems to update</caption>
      <thead>
        <tr>
          <th scope="col">Row</th>
          <th scope="col">System ID</th>
          <th scope="col">What changes</th>
          <th scope="col">SMO/Tech Lead</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={rowKey(row, index)}>
            <td>{row.sheetRow}</td>
            <td className="mono">{row.systemId}</td>
            <td>
              <ul className="plain-list">
                {row.before && row.after
                  ? changedFields(row.before, row.after).map((field) => (
                      <li key={field.label}>
                        {field.label}: {field.from} → {field.to}
                      </li>
                    ))
                  : null}
              </ul>
            </td>
            <td>{row.after ? <Smo row={row} email={row.after.smoEmail} /> : null}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RetireTable({ rows }: { rows: PreviewRow[] }) {
  return (
    <table className="data-table">
      <caption className="visually-hidden">Systems to retire</caption>
      <thead>
        <tr>
          <th scope="col">System ID</th>
          <th scope="col">System Name</th>
          <th scope="col">SMO/Tech Lead</th>
          <th scope="col">Cyber Manager</th>
          <th scope="col">Quarter</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={rowKey(row, index)}>
            <td className="mono">{row.systemId}</td>
            <td>{row.before?.name}</td>
            <td>{row.before?.smoEmail}</td>
            <td>{row.before?.cyberManagerEmail}</td>
            <td>{row.before?.quarter}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The in-flight VA Submissions a row touches, each by its state and VA Cycle. */
function InFlightList({ row }: { row: PreviewRow }) {
  return (
    <ul className="plain-list">
      {row.inFlight.map((work) => (
        <li key={work.submissionId}>
          {STATE_LABELS[work.state]} (VA Cycle {work.cycle})
        </li>
      ))}
    </ul>
  );
}

/** Why a row cannot be applied; nothing when it can. */
function Reasons({ row }: { row: PreviewRow }) {
  if (row.reasons.length === 0) return null;
  return (
    <ul className="plain-list">
      {row.reasons.map((reason) => (
        <li key={reason}>
          <span className="flag flag--warning">Blocked</span> {REASON_TEXT[reason]}
        </li>
      ))}
    </ul>
  );
}

interface WithdrawTableProps {
  rows: PreviewRow[];
  confirmed: ReadonlySet<string>;
  onConfirm: (submissionId: string, confirmed: boolean) => void;
}

/**
 * The Systems the file retires that have in-flight work (stories 6 and 7): each VA Submission is confirmed on its own
 * before the apply withdraws it. A row whose VA Submission has a file still being checked is blocked instead.
 */
function WithdrawTable({ rows, confirmed, onConfirm }: WithdrawTableProps) {
  return (
    <table className="data-table">
      <caption className="visually-hidden">VA Submissions that will be withdrawn</caption>
      <thead>
        <tr>
          <th scope="col">System ID</th>
          <th scope="col">System Name</th>
          <th scope="col">SMO/Tech Lead</th>
          <th scope="col">VA Submission</th>
          <th scope="col">Confirm</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={rowKey(row, index)}>
            <td className="mono">{row.systemId}</td>
            <td>{row.before?.name}</td>
            <td>{row.before?.smoEmail}</td>
            <td>
              <InFlightList row={row} />
            </td>
            <td>
              {row.reasons.length > 0 ? (
                <Reasons row={row} />
              ) : (
                row.inFlight.map((work) => (
                  <label key={work.submissionId}>
                    <input
                      type="checkbox"
                      checked={confirmed.has(work.submissionId)}
                      onChange={(event) => onConfirm(work.submissionId, event.target.checked)}
                    />{" "}
                    Withdraw the {STATE_LABELS[work.state]} VA Submission of {row.systemId}
                  </label>
                ))
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The Systems the file would move or transfer while they have in-flight work: shown, and blocked in Release 1. */
function InFlightChangeTable({ rows, caption }: { rows: PreviewRow[]; caption: string }) {
  return (
    <table className="data-table">
      <caption className="visually-hidden">{caption}</caption>
      <thead>
        <tr>
          <th scope="col">Row</th>
          <th scope="col">System ID</th>
          <th scope="col">What changes</th>
          <th scope="col">VA Submission</th>
          <th scope="col">Why it is blocked</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={rowKey(row, index)}>
            <td>{row.sheetRow}</td>
            <td className="mono">{row.systemId}</td>
            <td>
              <ul className="plain-list">
                {row.before && row.after
                  ? changedFields(row.before, row.after).map((field) => (
                      <li key={field.label}>
                        {field.label}: {field.from} → {field.to}
                      </li>
                    ))
                  : null}
              </ul>
            </td>
            <td>
              <InFlightList row={row} />
            </td>
            <td>
              <Reasons row={row} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function BlockedTable({ rows }: { rows: PreviewRow[] }) {
  return (
    <table className="data-table">
      <caption className="visually-hidden">Blocked rows</caption>
      <thead>
        <tr>
          <th scope="col">Row</th>
          <th scope="col">System ID</th>
          <th scope="col">Why it is blocked</th>
          <th scope="col">What the file says</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={rowKey(row, index)}>
            <td>{row.sheetRow}</td>
            <td className="mono">{row.systemId}</td>
            <td>
              <ul className="plain-list">
                {row.reasons.map((reason) => (
                  <li key={reason}>{REASON_TEXT[reason]}</li>
                ))}
              </ul>
            </td>
            <td>
              {row.after ? (
                <>
                  {row.after.name || "(no name)"}
                  <br />
                  <Smo row={row} email={row.after.smoEmail || "(no SMO/Tech Lead email)"} />
                  <br />
                  {row.after.cyberManagerEmail || "(no Cyber Manager email)"} · {row.after.quarter || "(no Quarter)"}
                </>
              ) : null}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Every VA Submission the apply would withdraw, each of which is confirmed on its own. */
export function withdrawals(preview: RegisterPreview): string[] {
  return preview.rows
    .filter((row) => row.effects.includes("WILL_WITHDRAW"))
    .flatMap((row) => row.inFlight.map((work) => work.submissionId));
}

interface RegisterPreviewPanelProps {
  preview: RegisterPreview;
  /** True while the apply is being sent. */
  applying: boolean;
  /** The VA Submissions confirmed for withdrawal so far. */
  confirmed: ReadonlySet<string>;
  onConfirm: (submissionId: string, confirmed: boolean) => void;
  onApply: () => void;
}

/** What the person can do with the preview: apply it, or why they cannot. */
function ApplyControl({ preview, applying, confirmed, onApply }: RegisterPreviewPanelProps) {
  if (preview.canApply) {
    const unconfirmed = withdrawals(preview).filter((id) => !confirmed.has(id)).length;
    return (
      <div className="notice-inline">
        <p>
          No row is blocked. Applying adds, updates and retires the Systems above and makes the SMO/Tech Lead and Cyber
          Manager roles follow the Register: anyone it no longer names loses access at once. It needs a sign-in from the
          last five minutes, and a retired System is never deleted.
          {preview.counts.willWithdraw > 0
            ? " Each VA Submission under Will withdraw becomes Withdrawn: read-only, out of the counts and reminders, and its SMO/Tech Lead is told."
            : null}
        </p>
        {unconfirmed > 0 ? (
          <p role="status">Confirm each VA Submission under Will withdraw first ({unconfirmed} still to confirm).</p>
        ) : null}
        <button type="button" className="button" disabled={applying || unconfirmed > 0} onClick={onApply}>
          {applying ? "Applying…" : "Apply these changes"}
        </button>
      </div>
    );
  }
  return (
    <p className="notice-inline notice-inline--refused" role="alert">
      {preview.counts.blocked > 0
        ? "This preview cannot be applied while any row is blocked. Correct the file and upload it again."
        : "This preview can no longer be applied. Upload the file again."}
    </p>
  );
}

/**
 * What an upload would do, before anything changes (stories 2, 5 and 12): its effects on in-flight work under Will
 * withdraw, Will move and Will transfer, the Systems to add, update and retire, and the rows that are blocked and why.
 * Everything from the file is rendered as text. A preview with a blocked row cannot be applied; one without can, with a
 * fresh sign-in, once each withdrawal is confirmed (stories 3, 4, 6, 73 and 79).
 */
export function RegisterPreviewPanel(props: RegisterPreviewPanelProps) {
  const { preview, confirmed, onConfirm } = props;
  const rows = (outcome: RegisterOutcome) => preview.rows.filter((row) => row.outcome === outcome);
  const affecting = (effect: RegisterEffect) => preview.rows.filter((row) => row.effects.includes(effect));
  // A row blocked for its effect on in-flight work is shown with that effect, below, not twice.
  const blocked = rows("BLOCKED").filter((row) => row.effects.length === 0);
  const { counts } = preview;
  return (
    <div className="preview" aria-live="polite">
      <h3>Preview of the changes</h3>
      <p>
        {counts.add} to add, {counts.update} to update, {counts.retire} to retire, {counts.noChange} unchanged,{" "}
        {counts.blocked} blocked. In-flight work: {counts.willWithdraw} to withdraw, {counts.willMove} to move,{" "}
        {counts.willTransfer} to transfer. {counts.unlinkedSmo} SMO/Tech Lead email{counts.unlinkedSmo === 1 ? "" : "s"}{" "}
        not yet linked to a sign-in.
      </p>
      <p className="muted">
        Nothing has changed. This preview is kept until {when(preview.expiresAt)} (Singapore time) and then discarded.
      </p>
      <ApplyControl {...props} />
      <Group
        title="Will withdraw"
        count={counts.willWithdraw}
        note="Systems the file leaves out whose VA Submission is in progress. Confirm each one: it becomes Withdrawn, read-only, and leaves the counts and reminders."
      >
        <WithdrawTable rows={affecting("WILL_WITHDRAW")} confirmed={confirmed} onConfirm={onConfirm} />
      </Group>
      <Group
        title="Will move"
        count={counts.willMove}
        note="Systems moved to another Quarter while their VA Submission is in progress. Blocked until Release 2."
      >
        <InFlightChangeTable rows={affecting("WILL_MOVE")} caption="Systems that would move with work in progress" />
      </Group>
      <Group
        title="Will transfer"
        count={counts.willTransfer}
        note="Systems given another SMO/Tech Lead or Cyber Manager while their VA Submission is in progress. Blocked until Release 2."
      >
        <InFlightChangeTable
          rows={affecting("WILL_TRANSFER")}
          caption="Systems that would transfer with work in progress"
        />
      </Group>
      <Group
        title="Blocked"
        count={blocked.length}
        note="Fix each row in the file; none of these rows would be applied."
      >
        <BlockedTable rows={blocked} />
      </Group>
      <Group title="Add" count={rows("ADD").length} note="Systems that are new to the Register.">
        <SystemTable rows={rows("ADD")} caption="Systems to add" />
      </Group>
      <Group title="Update" count={rows("UPDATE").length} note="Systems already in the Register whose details differ.">
        <UpdateTable rows={rows("UPDATE")} />
      </Group>
      <Group
        title="No change"
        count={rows("NO_CHANGE").length}
        note="Systems already in the Register exactly as the file says. Listed so an SMO/Tech Lead who has not signed in yet is visible."
      >
        <SystemTable rows={rows("NO_CHANGE")} caption="Systems with no change" />
      </Group>
      <Group
        title="Retire"
        count={rows("RETIRE").length}
        note="Systems the file leaves out. They are marked retired, never deleted, and keep their history."
      >
        <RetireTable rows={rows("RETIRE")} />
      </Group>
    </div>
  );
}
