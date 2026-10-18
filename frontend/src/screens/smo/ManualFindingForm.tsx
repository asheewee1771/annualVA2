import { useState, type FormEvent } from "react";
import { enterManualFinding, MANUAL_LIMITS, type Severity } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { SEVERITY_LABELS } from "./findingStatuses.ts";
import type { ScanReport } from "./scanReports.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

const SEVERITIES: readonly Severity[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFORMATIONAL"];

type TextField = keyof typeof MANUAL_LIMITS;

const TEXT_FIELDS: readonly { name: TextField; label: string }[] = [
  { name: "title", label: "Title" },
  { name: "identifier", label: "Identifier (CVE, rule or issue id)" },
  { name: "packageOrRule", label: "Package, rule, or host and path" },
];

const EMPTY: Readonly<Record<TextField, string>> = { title: "", identifier: "", packageOrRule: "" };

/**
 * Type in one Finding of a Scan Report whose tool cannot export a machine-readable report (story 23): its title,
 * identifier, package or rule and severity, each input with its classification label (IM8 dp-8) and the server's length
 * limit. The Finding joins the list below, marked Manually Entered, and needs a status like any other.
 */
export function ManualFindingForm({
  submissionId,
  report,
  classification,
  onEntered,
}: {
  submissionId: string;
  report: ScanReport;
  classification: string;
  onEntered: () => void;
}) {
  const [values, setValues] = useState<Record<TextField, string>>(EMPTY);
  const [severity, setSeverity] = useState<Severity>("HIGH");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const idOf = (name: string): string => `manual-${report.type}-${name}`;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setProblem(undefined);
    try {
      await enterManualFinding(submissionId, { reportType: report.type, ...values, severity });
      setValues(EMPTY);
      onEntered();
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)}>
      <h4>Enter a Finding by hand</h4>
      <p className="muted">Each Finding you enter is marked Manually Entered for the Cyber Manager to check closely.</p>
      {TEXT_FIELDS.map((field) => (
        <ClassifiedField key={field.name} label={field.label} classification={classification} id={idOf(field.name)}>
          <input
            id={idOf(field.name)}
            type="text"
            required
            maxLength={MANUAL_LIMITS[field.name]}
            value={values[field.name]}
            aria-invalid={problem?.field === field.name}
            onChange={(event) => setValues((current) => ({ ...current, [field.name]: event.target.value }))}
          />
        </ClassifiedField>
      ))}
      <ClassifiedField label="Severity" classification={classification} id={idOf("severity")}>
        <select
          id={idOf("severity")}
          value={severity}
          aria-invalid={problem?.field === "severity"}
          onChange={(event) => setSeverity(event.target.value as Severity)}
        >
          {SEVERITIES.map((each) => (
            <option key={each} value={each}>
              {SEVERITY_LABELS[each]}
            </option>
          ))}
        </select>
      </ClassifiedField>
      {problem ? <ErrorPanel problem={problem} /> : null}
      <p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? "Adding…" : "Add Finding"}
          <span className="visually-hidden"> to {report.title}</span>
        </button>
      </p>
    </form>
  );
}
