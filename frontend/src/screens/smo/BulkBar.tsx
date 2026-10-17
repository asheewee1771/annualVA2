import { useState, type FormEvent } from "react";
import { MAX_BATCH_SIZE, proposeInBulk, type FindingStatus } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import type { ReportFile } from "../../api/reportFiles.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { inputFor, statusSpec, STATUSES, type FieldName } from "./findingStatuses.ts";
import { ProposalFieldInput } from "./ProposalFieldInput.tsx";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

type Values = Partial<Record<FieldName, string>>;

/**
 * The sticky bulk bar (story 37): one status, with one set of the fields it requires (one detail, one target date), for
 * every ticked Finding in one step. The server makes one Bulk-set batch of it; the list and the progress are asked for
 * again afterwards. It is shown while at least one Finding is ticked, and refuses more than a batch may hold.
 */
export function BulkBar({
  submissionId,
  selectedIds,
  memos,
  classification,
  onApplied,
  onClear,
}: {
  submissionId: string;
  selectedIds: readonly string[];
  memos: readonly ReportFile[];
  classification: string;
  onApplied: () => void;
  onClear: () => void;
}) {
  const [status, setStatus] = useState<FindingStatus | "">("");
  const [values, setValues] = useState<Values>({});
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const count = selectedIds.length;
  const tooMany = count > MAX_BATCH_SIZE;

  async function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!status || tooMany) return;
    setBusy(true);
    setProblem(undefined);
    try {
      await proposeInBulk(submissionId, selectedIds, inputFor(statusSpec(status), values));
      setStatus("");
      setValues({});
      onApplied();
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form
      className="bulk-bar"
      aria-label="Apply one status to the selected Findings"
      onSubmit={(event) => void apply(event)}
    >
      <div className="bulk-bar__row">
        <strong>
          {count} {count === 1 ? "Finding" : "Findings"} selected
        </strong>
        <label htmlFor="bulk-status" className="visually-hidden">
          Status for the selected Findings
        </label>
        <select
          id="bulk-status"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as FindingStatus | "");
            setValues({});
            setProblem(undefined);
          }}
        >
          <option value="">Choose a status…</option>
          {STATUSES.map((spec) => (
            <option key={spec.value} value={spec.value}>
              {spec.label}
            </option>
          ))}
        </select>
        <button type="submit" className="button" disabled={busy || !status || tooMany}>
          {busy ? "Applying…" : `Apply to ${count}`}
        </button>
        <button type="button" className="button button--secondary" onClick={onClear} disabled={busy}>
          Clear selection
        </button>
      </div>
      {tooMany ? (
        <p className="flag flag--warning">One step takes at most {MAX_BATCH_SIZE} Findings. Narrow the selection.</p>
      ) : null}
      {status
        ? statusSpec(status).fields.map((field) => (
            <ProposalFieldInput
              key={`${status}-${field.name}`}
              idPrefix="bulk"
              spec={field}
              value={values[field.name] ?? ""}
              invalid={problem?.field === field.name}
              memos={memos}
              classification={classification}
              onChange={(name, value) => setValues((current) => ({ ...current, [name]: value }))}
            />
          ))
        : null}
      {problem ? <ErrorPanel problem={problem} /> : null}
    </form>
  );
}
