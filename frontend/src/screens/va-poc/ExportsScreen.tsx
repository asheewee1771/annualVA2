import { useCallback, useState, type FormEvent } from "react";
import { fetchCycleMatrix, type MatrixSystem } from "../../api/dashboards.ts";
import {
  downloadExport,
  fetchExports,
  requestExport,
  type Export,
  type ExportRequest,
  type ExportStatus,
} from "../../api/exports.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { takeRestoredDraft } from "../../api/stepUp.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { usePolling } from "../../hooks/usePolling.ts";
import { useResource } from "../../hooks/useResource.ts";
import { CycleSelector } from "../shared/CycleSelector.tsx";
import { saveFile } from "../shared/saveFile.ts";

const INTENT = "export-mgmt-summary";
const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

const STATUS_LABELS: Readonly<Record<ExportStatus, string>> = {
  QUEUED: "Being made",
  READY: "Ready",
  FAILED: "Failed: ask again",
  EXPIRED: "Expired",
};

const CLASSIFICATION_LABELS: Readonly<Record<Export["classification"], string>> = {
  CONFIDENTIAL: "Confidential",
  RESTRICTED: "Restricted / Sensitive-Normal",
};

function problemOf(failure: unknown): Problem {
  return failure instanceof ApiError ? failure.problem : UNREACHABLE;
}

/** What the form holds: the Cycle (the running one when not chosen), the Quarter (the whole Cycle when none), the Systems. */
interface Scope {
  cycle: number | undefined;
  quarter: number | undefined;
  /** The Systems chosen one by one, or undefined for every System. */
  systems: string[] | undefined;
}

/** The request the person was making before a step-up, when it was this screen's; otherwise an empty form. */
function restoredRequest(): Scope {
  const draft = takeRestoredDraft(INTENT);
  const asked: Partial<ExportRequest> = typeof draft === "object" && draft !== null ? (draft as ExportRequest) : {};
  return { cycle: asked.cycle, quarter: asked.quarter, systems: asked.systems };
}

/** The Systems one may choose: those of the Quarter asked for (or of the whole Cycle), Withdrawn left out. */
function listedIn(systems: MatrixSystem[] | undefined, quarter: number | undefined): MatrixSystem[] {
  return (systems ?? []).filter(
    (system) => system.state !== "WITHDRAWN" && (quarter === undefined || system.quarter === quarter),
  );
}

/** The body of the request: only the chosen Systems that are still listed for the Quarter asked for. */
function summaryRequest(
  cycle: number | undefined,
  quarter: number | undefined,
  chosen: string[] | undefined,
  listed: MatrixSystem[],
): ExportRequest {
  const asked: ExportRequest = { type: "MGMT_SUMMARY", format: "XLSX" };
  if (cycle !== undefined) asked.cycle = cycle;
  if (quarter !== undefined) asked.quarter = quarter;
  if (chosen !== undefined) asked.systems = chosen.filter((id) => listed.some((system) => system.id === id));
  return asked;
}

/** The Quarter the summary is about, and whether it covers every System or those chosen. */
function ScopeFields({
  classification,
  quarter,
  onQuarter,
  some,
  onSome,
}: {
  classification: string;
  quarter: number | undefined;
  onQuarter: (quarter: number | undefined) => void;
  some: boolean;
  onSome: (some: boolean) => void;
}) {
  return (
    <>
      <ClassifiedField label="Quarter" classification={classification} id="export-quarter">
        <select
          id="export-quarter"
          value={quarter ?? ""}
          onChange={(event) => onQuarter(event.target.value === "" ? undefined : Number(event.target.value))}
        >
          <option value="">The whole VA Cycle</option>
          {[1, 2, 3, 4].map((number) => (
            <option key={number} value={number}>
              Q{number}
            </option>
          ))}
        </select>
      </ClassifiedField>
      <ClassifiedField label="Covers" classification={classification} id="export-covers">
        <select
          id="export-covers"
          value={some ? "some" : "every"}
          onChange={(event) => onSome(event.target.value === "some")}
        >
          <option value="every">Every System (Confidential)</option>
          <option value="some">Systems I choose</option>
        </select>
      </ClassifiedField>
    </>
  );
}

/** Ticks one System per row; the Systems are those of the Quarter (or Cycle) asked for, Withdrawn left out. */
function SystemChoice({
  systems,
  chosen,
  onChange,
  classification,
}: {
  systems: MatrixSystem[];
  chosen: string[];
  onChange: (chosen: string[]) => void;
  classification: string;
}) {
  return (
    <fieldset className="plain-fieldset">
      <legend>
        Systems <span className="field__classification muted">({classification})</span>
      </legend>
      <ul className="plain-list">
        {systems.map((system) => (
          <li key={system.id}>
            <label>
              <input
                type="checkbox"
                checked={chosen.includes(system.id)}
                onChange={(event) =>
                  onChange(
                    event.target.checked ? [...chosen, system.id] : chosen.filter((other) => other !== system.id),
                  )
                }
              />{" "}
              {system.systemId} {system.name} (Q{system.quarter})
            </label>
          </li>
        ))}
      </ul>
    </fieldset>
  );
}

/**
 * Asks for a management summary (story 81). It needs a sign-in from the last five minutes: without one the app explains
 * the fresh sign-in, brings the VA POC back here and fills the form in again, to press Ask again. Every System of the
 * Quarter or Cycle makes it Confidential; some of them, Restricted / Sensitive-Normal (story 86). The server decides.
 */
function SummaryForm({ classification, onQueued }: { classification: string; onQueued: () => void }) {
  const [restored] = useState(restoredRequest);
  const [cycle, setCycle] = useState(restored.cycle);
  const [quarter, setQuarter] = useState(restored.quarter);
  const [chosen, setChosen] = useState(restored.systems);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const loadMatrix = useCallback(() => fetchCycleMatrix(cycle), [cycle]);
  const matrix = useResource(loadMatrix);
  const listed = listedIn(matrix.data?.systems, quarter);

  async function ask(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(undefined);
    try {
      await requestExport(summaryRequest(cycle, quarter, chosen, listed));
      onQueued();
    } catch (failure) {
      setProblem(problemOf(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" aria-labelledby="summary-title" onSubmit={(event) => void ask(event)}>
      <h3 id="summary-title">Management summary (Excel)</h3>
      <p className="muted">
        Counts by Quarter and state with Endorsed Late, and a table of Systems with their SMO/Tech Lead, state, Overdue
        and open Deviations. It holds no vulnerability detail. Every System of the Quarter or Cycle makes it
        Confidential; some of them make it Restricted / Sensitive-Normal. You need to have signed in within the last
        five minutes. It is made in the background and kept for 24 hours, for you alone to download.
      </p>
      <CycleSelector
        id="export-cycle"
        classification={classification}
        selected={cycle ?? matrix.data?.cycle}
        onSelect={setCycle}
      />
      <ScopeFields
        classification={classification}
        quarter={quarter}
        onQuarter={setQuarter}
        some={chosen !== undefined}
        onSome={(some) => setChosen(some ? [] : undefined)}
      />
      {matrix.error ? <ErrorPanel problem={matrix.error.problem} /> : null}
      {chosen === undefined ? null : (
        <SystemChoice systems={listed} chosen={chosen} onChange={setChosen} classification={classification} />
      )}
      {problem ? <ErrorPanel problem={problem} /> : null}
      <p>
        <button type="submit" className="button" disabled={busy}>
          Ask for the summary
        </button>
      </p>
    </form>
  );
}

/** What an export covers, in words. */
function covers(row: Export): string {
  return `VA Cycle ${row.cycle}${row.quarter === null ? "" : `, Q${row.quarter}`}, ${row.systemCount} Systems`;
}

function ExportRow({ row, onProblem }: { row: Export; onProblem: (problem: Problem | undefined) => void }) {
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    onProblem(undefined);
    try {
      saveFile(await downloadExport(row.id));
    } catch (failure) {
      onProblem(problemOf(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr>
      <td>{new Date(row.requestedAt).toLocaleString()}</td>
      <td>{covers(row)}</td>
      <td>{CLASSIFICATION_LABELS[row.classification]}</td>
      <td>
        {STATUS_LABELS[row.status]}
        {row.status === "READY" && row.expiresAt !== null ? (
          <>
            <br />
            <small className="muted">Kept until {new Date(row.expiresAt).toLocaleString()}</small>
          </>
        ) : null}
      </td>
      <td>
        {row.status === "READY" ? (
          <button type="button" className="button button--secondary" disabled={busy} onClick={() => void save()}>
            Download
          </button>
        ) : null}
      </td>
    </tr>
  );
}

/**
 * The VA POC's exports (stories 81 and 86): ask for a management summary, watch it being made (the list is read again
 * every few seconds while one is), and download it once it is ready.
 */
export function ExportsScreen({ classification }: { classification: string }) {
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const exports = useResource(fetchExports);
  const making = (exports.data ?? []).some((row) => row.status === "QUEUED");
  const { slow } = usePolling(making, exports.loading, exports.reload);

  return (
    <div className="card">
      <h2>Exports</h2>
      <SummaryForm classification={classification} onQueued={exports.reload} />
      <section className="card" aria-labelledby="exports-title">
        <h3 id="exports-title">Your exports</h3>
        {exports.error ? <ErrorPanel problem={exports.error.problem} /> : null}
        {problem ? <ErrorPanel problem={problem} /> : null}
        {slow ? <p className="muted">This is taking longer than usual; it is still being made.</p> : null}
        {exports.data && exports.data.length === 0 ? (
          <p className="muted">You have asked for none in two days.</p>
        ) : null}
        {exports.data && exports.data.length > 0 ? (
          <div className="table-scroll">
            <table className="data-table">
              <caption className="visually-hidden">Your exports of the last two days, newest first</caption>
              <thead>
                <tr>
                  <th scope="col">Asked for</th>
                  <th scope="col">Covers</th>
                  <th scope="col">Classification</th>
                  <th scope="col">Status</th>
                  <th scope="col">File</th>
                </tr>
              </thead>
              <tbody>
                {exports.data.map((row) => (
                  <ExportRow key={row.id} row={row} onProblem={setProblem} />
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </section>
    </div>
  );
}
