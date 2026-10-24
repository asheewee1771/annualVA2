import type { PreviewRow, RegisterOutcome, RegisterPreview } from "../../api/systemRegister.ts";
import { Group, InFlightEffects } from "./InFlightEffects.tsx";
import { REASON_TEXT, changedFields, rowKey, withdrawals } from "./registerPreviewText.ts";

/** Business times are shown in Singapore time, whatever the browser's own zone is. */
const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });

function when(instant: string): string {
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? instant : WHEN.format(date);
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
      {preview.counts.blocked + preview.counts.heldBack > 0
        ? "This preview cannot be applied while any row is blocked. Correct the file, or wait for what holds a row back, and upload it again."
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
  // The outcome groups list what the apply would do; a row its in-flight work holds back is listed under its effect.
  const rows = (outcome: RegisterOutcome) =>
    preview.rows.filter((row) => row.outcome === outcome && (outcome === "BLOCKED" || row.reasons.length === 0));
  const blocked = rows("BLOCKED");
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
      <InFlightEffects preview={preview} confirmed={confirmed} onConfirm={onConfirm} />
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
