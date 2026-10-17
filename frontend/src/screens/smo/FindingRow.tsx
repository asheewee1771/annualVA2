import { useState, type ChangeEvent, type FormEvent } from "react";
import { proposeStatus, type Finding, type FindingStatus } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import type { ReportFile } from "../../api/reportFiles.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import {
  inputFor,
  proposalDetail,
  REVIEW_LABELS,
  SEVERITY_LABELS,
  statusSpec,
  STATUSES,
  type FieldName,
  type FieldSpec,
} from "./findingStatuses.ts";
import { SCAN_REPORTS } from "./scanReports.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

const REPORT_TITLES = new Map(SCAN_REPORTS.map((report) => [report.type, report.title]));

type Values = Partial<Record<FieldName, string>>;

/** One input of the status's fields, with its classification label beside it (IM8 dp-8). */
function FieldInput({
  finding,
  spec,
  value,
  invalid,
  memos,
  classification,
  onChange,
}: {
  finding: Finding;
  spec: FieldSpec;
  value: string;
  invalid: boolean;
  memos: readonly ReportFile[];
  classification: string;
  onChange: (name: FieldName, value: string) => void;
}) {
  const id = `${finding.id}-${spec.name}`;
  const change = (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    onChange(spec.name, event.target.value);
  const common = { id, value, onChange: change, "aria-invalid": invalid, required: spec.required } as const;
  return (
    <ClassifiedField label={spec.label} classification={classification} id={id}>
      {spec.kind === "text" ? <textarea {...common} rows={3} maxLength={spec.maxLength} /> : null}
      {spec.kind === "line" ? <input {...common} type="text" maxLength={spec.maxLength} /> : null}
      {spec.kind === "date" ? <input {...common} type="date" /> : null}
      {spec.kind === "memo" ? (
        <select {...common}>
          <option value="">No approval memo</option>
          {memos.map((memo) => (
            <option key={memo.id} value={memo.id}>
              {memo.name}
            </option>
          ))}
        </select>
      ) : null}
    </ClassifiedField>
  );
}

/** What the SMO/Tech Lead fills in to propose a status: the fields it requires, and the button that sends them. */
function ProposalForm({
  finding,
  status,
  memos,
  classification,
  onProposed,
}: {
  finding: Finding;
  status: FindingStatus;
  memos: readonly ReportFile[];
  classification: string;
  onProposed: () => void;
}) {
  const spec = statusSpec(status);
  const [values, setValues] = useState<Values>({});
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setProblem(undefined);
    try {
      await proposeStatus(finding.id, inputFor(spec, values));
      onProposed();
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)}>
      {spec.fields.map((field) => (
        <FieldInput
          key={field.name}
          finding={finding}
          spec={field}
          value={values[field.name] ?? ""}
          invalid={problem?.field === field.name}
          memos={memos}
          classification={classification}
          onChange={(name, value) => setValues((current) => ({ ...current, [name]: value }))}
        />
      ))}
      {problem ? <ErrorPanel problem={problem} /> : null}
      <p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? "Saving…" : `Propose ${spec.label}`}
          <span className="visually-hidden"> for {finding.identifier}</span>
        </button>
      </p>
    </form>
  );
}

/**
 * One Finding as a row of the list, and the control to propose a status for it (stories 28 and 35): the severity, what the
 * report called it, where it was found, the status proposed so far with the review of it, and a select of the four statuses
 * that opens the fields the chosen one requires. Every text was typed by a person or read from a report, so it is text.
 */
export function FindingRow({
  finding,
  memos,
  classification,
  onProposed,
}: {
  finding: Finding;
  memos: readonly ReportFile[];
  classification: string;
  onProposed: () => void;
}) {
  const [chosen, setChosen] = useState<FindingStatus | "">("");
  const proposal = finding.currentProposal;
  const selectId = `${finding.id}-status`;

  return (
    <>
      <tr>
        <td>
          <span
            className={finding.severity === "CRITICAL" || finding.severity === "HIGH" ? "flag flag--danger" : "flag"}
          >
            {SEVERITY_LABELS[finding.severity]}
          </span>
        </td>
        <td className="mono">
          {finding.identifier}
          {finding.confidence === "TENTATIVE" ? (
            <>
              {" "}
              <span className="flag flag--warning">Tentative</span>
            </>
          ) : null}
          {finding.didNotComplete ? (
            <>
              {" "}
              <span className="flag flag--warning">Check did not complete</span>
            </>
          ) : null}
        </td>
        <td className="mono">{finding.packageOrRule}</td>
        <td>{REPORT_TITLES.get(finding.reportType) ?? finding.reportType}</td>
        <td>
          {finding.occurrenceCount}
          <span className="visually-hidden"> places</span>
        </td>
        <td>
          {proposal ? (
            <>
              <strong>{statusSpec(proposal.status).label}</strong>
              {finding.reviewState ? <span className="muted"> · {REVIEW_LABELS[finding.reviewState]}</span> : null}
              <p className="muted finding-detail">{proposalDetail(proposal)}</p>
            </>
          ) : (
            <span className="flag flag--warning">No status proposed</span>
          )}
        </td>
        <td>
          <label htmlFor={selectId} className="visually-hidden">
            Propose a status for {finding.identifier}
          </label>
          <select
            id={selectId}
            value={chosen}
            onChange={(event) => setChosen(event.target.value as FindingStatus | "")}
          >
            <option value="">{proposal ? "Change status…" : "Propose a status…"}</option>
            {STATUSES.map((status) => (
              <option key={status.value} value={status.value}>
                {status.label}
              </option>
            ))}
          </select>
        </td>
      </tr>
      {chosen ? (
        <tr>
          <td colSpan={7}>
            <ProposalForm
              key={chosen}
              finding={finding}
              status={chosen}
              memos={memos}
              classification={classification}
              onProposed={() => {
                setChosen("");
                onProposed();
              }}
            />
          </td>
        </tr>
      ) : null}
    </>
  );
}
