import { dayLabel } from "../../api/cycles.ts";
import type { QueuedConfirmation } from "../../api/exemptions.ts";

const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });

function ConfirmationRow({
  confirmation,
  busy,
  onConfirm,
}: {
  confirmation: QueuedConfirmation;
  busy: boolean;
  onConfirm: (confirmation: QueuedConfirmation) => void;
}) {
  return (
    <tr>
      <td>
        <span className="mono">{confirmation.systemCode}</span> {confirmation.systemName}
      </td>
      <td>
        VA Cycle {confirmation.cycle}, Q{confirmation.quarter}
      </td>
      <td>{dayLabel(confirmation.vaDate)}</td>
      <td>{dayLabel(confirmation.vaHappenedOn)}</td>
      <td>
        <p className="finding-detail">{confirmation.vaEvidence}</p>
      </td>
      <td>{WHEN.format(new Date(confirmation.vaRecordedAt))}</td>
      <td>
        {confirmation.sodConflict ? (
          <span className="flag flag--warning">The other VA POC confirms</span>
        ) : (
          <button type="button" className="button" disabled={busy} onClick={() => onConfirm(confirmation)}>
            Confirm VA<span className="visually-hidden"> for {confirmation.systemCode}</span>
          </button>
        )}
      </td>
    </tr>
  );
}

/**
 * The recorded VAs of conditional Exemptions waiting on a VA POC (story 47), from every VA Cycle, the earliest recorded
 * first, with what the SMO/Tech Lead wrote as text. Confirming makes the Exemption unconditional; it needs a sign-in from
 * the last five minutes, which the screen's error panel explains.
 */
export function VaConfirmations({
  confirmations,
  busy,
  onConfirm,
}: {
  confirmations: QueuedConfirmation[];
  busy: boolean;
  onConfirm: (confirmation: QueuedConfirmation) => void;
}) {
  return (
    <section aria-labelledby="va-confirmations">
      <h3 id="va-confirmations">Scheduled VAs to confirm</h3>
      <p className="muted">
        The SMO/Tech Lead recorded that the VA of a conditional Exemption happened. Once confirmed, the Exemption is no
        longer conditional.
      </p>
      {confirmations.length === 0 ? (
        <p className="muted">No recorded VA is waiting on a confirmation.</p>
      ) : (
        <div className="table-scroll">
          <table className="data-table">
            <caption className="visually-hidden">Recorded VAs waiting on a confirmation</caption>
            <thead>
              <tr>
                <th scope="col">System</th>
                <th scope="col">Quarter</th>
                <th scope="col">Scheduled for</th>
                <th scope="col">Happened on</th>
                <th scope="col">Evidence</th>
                <th scope="col">Recorded</th>
                <th scope="col">
                  <span className="visually-hidden">Confirmation</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {confirmations.map((confirmation) => (
                <ConfirmationRow
                  key={confirmation.exemptionId}
                  confirmation={confirmation}
                  busy={busy}
                  onConfirm={onConfirm}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
