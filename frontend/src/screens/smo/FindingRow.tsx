import { useState, type FormEvent } from "react";
import {
  deleteManualFinding,
  proposeStatus,
  type Finding,
  type FindingStatus,
  type SourceFile,
} from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { fetchReportFileContent, type ReportFile } from "../../api/reportFiles.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import {
  inputFor,
  proposalDetail,
  REVIEW_LABELS,
  SEVERITY_LABELS,
  severityClass,
  statusSpec,
  STATUSES,
  type FieldName,
} from "../shared/findingStatuses.ts";
import { ProposedAsMark } from "../shared/FindingCells.tsx";
import { saveFile } from "../shared/saveFile.ts";
import { ConfirmDelete } from "./ConfirmDelete.tsx";
import { Editable } from "./Editable.tsx";
import { ProposalFieldInput } from "./ProposalFieldInput.tsx";
import { reportTitle } from "../shared/scanReports.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

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

/**
 * The file a rescan replaced, where a Finding the rescan proposed Remediated was last reported (story 39): its name, as
 * text, and a download through the file endpoint, so the Cyber Manager can check what the old scan said. A deleted file
 * (ticket 60) is named only: it can no longer be downloaded.
 */
function ReplacedFile({
  submissionId,
  file,
  deleted,
  onProblem,
}: {
  submissionId: string;
  file: SourceFile;
  deleted: boolean;
  onProblem: (problem: Problem | undefined) => void;
}) {
  if (deleted) return <p className="muted">Last reported in {file.name}, which was deleted</p>;
  async function save() {
    onProblem(undefined);
    try {
      saveFile(await fetchReportFileContent(submissionId, file));
    } catch (failure) {
      onProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    }
  }

  return (
    <p className="muted">
      Last reported in {file.name}{" "}
      <button type="button" className="button button--secondary" onClick={() => void save()}>
        Download<span className="visually-hidden"> {file.name}</span>
      </button>
    </p>
  );
}

/**
 * The status proposed so far, its review, whether it was Bulk-set or made by a rescan, and what was proposed; or that there
 * is none yet.
 */
function StatusCell({ submissionId, finding }: { submissionId: string; finding: Finding }) {
  const proposal = finding.currentProposal;
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
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
      <ProposedAsMark proposal={proposal} />
      <p className="muted finding-detail">{proposalDetail(proposal)}</p>
      {proposal.replacedFile ? (
        <ReplacedFile
          submissionId={submissionId}
          file={proposal.replacedFile}
          deleted={proposal.proposedAs === "FILE_DELETED"}
          onProblem={setProblem}
        />
      ) : null}
      {problem ? <ErrorPanel problem={problem} /> : null}
    </td>
  );
}

/** What the report called the Finding, with its title and flags: Manually Entered, Tentative, a check that did not complete. */
function IdentifierCell({ finding }: { finding: Finding }) {
  return (
    <td className="mono">
      {finding.identifier}
      {finding.source === "MANUAL" ? (
        <>
          {" "}
          <span className="flag flag--warning">Manually Entered</span>
        </>
      ) : null}
      {finding.title !== null ? <p className="muted finding-detail">{finding.title}</p> : null}
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
  );
}

/**
 * Deletes a Finding entered by hand that has no status yet (ticket 60), once asked. A deleted one's row goes, so the
 * caller reads the list again and moves the focus; a refused one keeps its row, shows the server's problem and has the list
 * read again.
 */
function DeleteFinding({
  finding,
  onRefused,
  onDeleted,
}: {
  finding: Finding;
  onRefused: () => void;
  onDeleted: (findingId: string) => void;
}) {
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function remove(): Promise<boolean> {
    setProblem(undefined);
    try {
      await deleteManualFinding(finding.id);
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
      onRefused();
      return false;
    }
    onDeleted(finding.id);
    return true;
  }

  return (
    <>
      <p>
        <ConfirmDelete
          name={finding.identifier}
          what="Finding"
          consequence="Delete this Finding entered by mistake?"
          onDelete={remove}
        />
      </p>
      {problem ? <ErrorPanel problem={problem} /> : null}
    </>
  );
}

/**
 * One Finding as a row of the list, and the control to propose a status for it (stories 28 and 35): the severity, what the
 * report called it, where it was found, the status proposed so far with the review of it, and a select of the four statuses
 * that opens the fields the chosen one requires. A Finding entered by hand with no status yet can be deleted (ticket 60).
 * Every text was typed by a person or read from a report, so it is text.
 */
export function FindingRow({
  submissionId,
  finding,
  selected,
  onToggle,
  memos,
  classification,
  onProposed,
  onDeleted,
}: {
  submissionId: string;
  finding: Finding;
  /** Whether its tick box is ticked, for the bulk bar. */
  selected: boolean;
  onToggle: (findingId: string) => void;
  memos: readonly ReportFile[];
  classification: string;
  onProposed: () => void;
  /** The Finding was deleted: its row is about to go, so the list is read again and the focus moved. */
  onDeleted: (findingId: string) => void;
}) {
  const [chosen, setChosen] = useState<FindingStatus | "">("");
  const proposal = finding.currentProposal;
  const selectId = `${finding.id}-status`;
  const tickId = `${finding.id}-tick`;

  return (
    <>
      <tr className={selected ? "row--selected" : undefined}>
        <td>
          <Editable>
            <input id={tickId} type="checkbox" checked={selected} onChange={() => onToggle(finding.id)} />
            <label htmlFor={tickId} className="visually-hidden">
              Select {finding.identifier}
            </label>
          </Editable>
        </td>
        <td>
          <span className={severityClass(finding.severity)}>{SEVERITY_LABELS[finding.severity]}</span>
        </td>
        <IdentifierCell finding={finding} />
        <td className="mono">{finding.packageOrRule}</td>
        <td>{reportTitle(finding.reportType)}</td>
        <td>
          {finding.occurrenceCount}
          <span className="visually-hidden"> places</span>
        </td>
        <StatusCell submissionId={submissionId} finding={finding} />
        <td>
          <Editable>
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
            {finding.source === "MANUAL" && proposal === null ? (
              <DeleteFinding finding={finding} onRefused={onProposed} onDeleted={onDeleted} />
            ) : null}
          </Editable>
        </td>
      </tr>
      {chosen ? (
        <tr>
          <td colSpan={8}>
            <Editable>
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
            </Editable>
          </td>
        </tr>
      ) : null}
    </>
  );
}
