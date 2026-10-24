import { useState, type FormEvent } from "react";
import { ApiError, type Problem } from "../../api/problem.ts";
import type { ReportType } from "../../api/reportFiles.ts";
import {
  MAX_JUSTIFICATION_LENGTH,
  setScanReportMode,
  type ReportMode,
  type ScanReportMode,
} from "../../api/scanReports.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { REVIEW_LABELS } from "../shared/findingStatuses.ts";
import type { ScanReport } from "../shared/scanReports.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

const MODE_LABELS: Readonly<Record<ReportMode, string>> = {
  UPLOAD: "Upload the tool's report",
  MANUAL: "Enter the Findings by hand: the tool cannot export a machine-readable report",
  NOT_APPLICABLE: "Not applicable to this System",
};

const JUSTIFICATION_LABELS: Readonly<Record<ReportMode, string>> = {
  UPLOAD: "",
  MANUAL: "Why the tool's report cannot be uploaded",
  NOT_APPLICABLE: "Why it does not apply",
};

/** The modes each Scan Report offers: Secure Configuration is always required, so it is never not applicable. */
const MODES: Readonly<Record<ReportType, readonly ReportMode[]>> = {
  DEPENDENCY_CHECK: ["UPLOAD", "MANUAL", "NOT_APPLICABLE"],
  SECURE_CONFIGURATION: ["UPLOAD", "MANUAL"],
  BURP_SUITE: ["UPLOAD", "MANUAL", "NOT_APPLICABLE"],
};

/** What the Scan Report is now, in words, with its justification as text and the review of a not-applicable one. */
function CurrentMode({ current }: { current: ScanReportMode }) {
  const review = current.reviewState ? ` (${REVIEW_LABELS[current.reviewState]})` : "";
  const words: Readonly<Record<ReportMode, string>> = {
    UPLOAD: "Its report is uploaded.",
    MANUAL: "Its Findings are entered by hand.",
    NOT_APPLICABLE: `Marked not applicable${review}.`,
  };
  return (
    <>
      <p>{words[current.mode]}</p>
      {current.justification !== null ? <p className="muted finding-detail">{current.justification}</p> : null}
    </>
  );
}

/**
 * How one Scan Report is provided (story 23): the tool's report is uploaded, its Findings are entered by hand, or (not for
 * Secure Configuration, which is always required) it is not applicable. Both of the last two need a justification, and a
 * not-applicable one is reviewed by the Cyber Manager like a Finding. The server refuses a change while the Scan Report
 * holds a report file or a Finding, and says so here. The justification is shown and sent as text only.
 */
export function ScanReportModeForm({
  submissionId,
  report,
  current,
  classification,
  onChanged,
}: {
  submissionId: string;
  report: ScanReport;
  current: ScanReportMode;
  classification: string;
  onChanged: () => void;
}) {
  const [mode, setMode] = useState<ReportMode>(current.mode);
  const [justification, setJustification] = useState(current.justification ?? "");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const modeId = `mode-${report.type}`;
  const justificationId = `justification-${report.type}`;
  const unchanged = mode === current.mode && justification === (current.justification ?? "");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setProblem(undefined);
    try {
      await setScanReportMode(submissionId, report.type, mode, justification);
      onChanged();
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)}>
      <CurrentMode current={current} />
      <ClassifiedField label="How this Scan Report is provided" classification={classification} id={modeId}>
        <select
          id={modeId}
          value={mode}
          aria-invalid={problem?.field === "mode"}
          onChange={(event) => setMode(event.target.value as ReportMode)}
        >
          {MODES[report.type].map((each) => (
            <option key={each} value={each}>
              {MODE_LABELS[each]}
            </option>
          ))}
        </select>
      </ClassifiedField>
      {mode === "UPLOAD" ? null : (
        <ClassifiedField label={JUSTIFICATION_LABELS[mode]} classification={classification} id={justificationId}>
          <textarea
            id={justificationId}
            rows={3}
            required
            maxLength={MAX_JUSTIFICATION_LENGTH}
            value={justification}
            aria-invalid={problem?.field === "justification"}
            onChange={(event) => setJustification(event.target.value)}
          />
        </ClassifiedField>
      )}
      {problem ? <ErrorPanel problem={problem} /> : null}
      <p>
        <button type="submit" className="button button--secondary" disabled={busy || unchanged}>
          {busy ? "Saving…" : "Save"}
          <span className="visually-hidden"> how {report.title} is provided</span>
        </button>
      </p>
    </form>
  );
}
