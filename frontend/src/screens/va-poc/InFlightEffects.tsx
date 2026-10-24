import type { ReactNode } from "react";
import type { PreviewRow, RegisterEffect, RegisterPreview } from "../../api/systemRegister.ts";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { REASON_TEXT, changedFields, rowKey } from "./registerPreviewText.ts";

/** One group of the preview, with its count and what it means; nothing when it is empty. */
export function Group({
  title,
  note,
  count,
  children,
}: {
  title: string;
  note: string;
  count: number;
  children: ReactNode;
}) {
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

/** What holds a row back; nothing when nothing does. */
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

interface ConfirmProps {
  /** The VA Submissions confirmed for withdrawal so far. */
  confirmed: ReadonlySet<string>;
  onConfirm: (submissionId: string, confirmed: boolean) => void;
}

/** One tick per VA Submission of a row, or what holds the row back. */
function Confirm({ row, confirmed, onConfirm }: ConfirmProps & { row: PreviewRow }) {
  if (row.reasons.length > 0) return <Reasons row={row} />;
  return (
    <>
      {row.inFlight.map((work) => (
        <label key={work.submissionId}>
          <input
            type="checkbox"
            checked={confirmed.has(work.submissionId)}
            onChange={(event) => onConfirm(work.submissionId, event.target.checked)}
          />{" "}
          Withdraw the {STATE_LABELS[work.state]} VA Submission of {row.systemId}
        </label>
      ))}
    </>
  );
}

/**
 * The Systems the file retires that have in-flight work (stories 6 and 7): each VA Submission is confirmed on its own
 * before the apply withdraws it. A row whose VA Submission has a file still being checked is held back instead.
 */
function WithdrawTable({ rows, ...confirm }: ConfirmProps & { rows: PreviewRow[] }) {
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
              <Confirm row={row} {...confirm} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The Systems the file would move or transfer while they have in-flight work: shown, and held back in Release 1. */
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

/**
 * What the file would do to in-flight work (story 5): Will withdraw, with a tick per VA Submission (story 6), and Will
 * move and Will transfer, each with what holds it back in Release 1.
 */
export function InFlightEffects({ preview, ...confirm }: ConfirmProps & { preview: RegisterPreview }) {
  const affecting = (effect: RegisterEffect) => preview.rows.filter((row) => row.effects.includes(effect));
  const { counts } = preview;
  return (
    <>
      <Group
        title="Will withdraw"
        count={counts.willWithdraw}
        note="Systems the file leaves out whose VA Submission is in progress. Confirm each one: it becomes Withdrawn, read-only, and leaves the counts and reminders."
      >
        <WithdrawTable rows={affecting("WILL_WITHDRAW")} {...confirm} />
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
    </>
  );
}
