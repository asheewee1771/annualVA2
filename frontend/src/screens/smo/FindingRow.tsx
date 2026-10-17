import { useState, type FormEvent } from "react";
import { proposeStatus, type Finding, type FindingStatus } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import type { ReportFile } from "../../api/reportFiles.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import {
  inputFor,
  proposalDetail,
  REVIEW_LABELS,
  SEVERITY_LABELS,
  statusSpec,
  STATUSES,
  type FieldName,
} from "./findingStatuses.ts";
import { ProposalFieldInput } from "./ProposalFieldInput.tsx";
import { SCAN_REPORTS } from "./scanReports.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

const REPORT_TITLES = new Map(SCAN_REPORTS.map((report) => [report.type, report.title]));

type Values = Partial<Record<FieldName, string>>;

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
        <ProposalFieldInput
          key={field.name}
          idPrefix={finding.id}
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

/** The status proposed so far, its review, whether it was Bulk-set, and what was proposed; or that there is none yet. */
function StatusCell({ finding }: { finding: Finding }) {
  const proposal = finding.currentProposal;
  if (!proposal) {
    return (
      <td>
        <span className="flag flag--warning">No status proposed</span>
      </td>
    );
  }
  return (
    <td>
      <strong>{statusSpec(proposal.status).label}</strong>
      {finding.reviewState ? <span className="muted"> · {REVIEW_LABELS[finding.reviewState]}</span> : null}
      {proposal.batchId ? <span className="muted"> · Bulk-set</span> : null}
      <p className="muted finding-detail">{proposalDetail(proposal)}</p>
    </td>
  );
}

/**
 * One Finding as a row of the list, and the control to propose a status for it (stories 28 and 35): the severity, what the
 * report called it, where it was found, the status proposed so far with the review of it, and a select of the four statuses
 * that opens the fields the chosen one requires. Every text was typed by a person or read from a report, so it is text.
 */
export function FindingRow({
  finding,
  selected,
  onToggle,
  memos,
  classification,
  onProposed,
}: {
  finding: Finding;
  /** Whether its tick box is ticked, for the bulk bar. */
  selected: boolean;
  onToggle: (findingId: string) => void;
  memos: readonly ReportFile[];
  classification: string;
  onProposed: () => void;
}) {
  const [chosen, setChosen] = useState<FindingStatus | "">("");
  const proposal = finding.currentProposal;
  const selectId = `${finding.id}-status`;
  const tickId = `${finding.id}-tick`;

  return (
    <>
      <tr className={selected ? "row--selected" : undefined}>
        <td>
          <input id={tickId} type="checkbox" checked={selected} onChange={() => onToggle(finding.id)} />
          <label htmlFor={tickId} className="visually-hidden">
            Select {finding.identifier}
          </label>
        </td>
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
        <StatusCell finding={finding} />
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
          <td colSpan={8}>
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
