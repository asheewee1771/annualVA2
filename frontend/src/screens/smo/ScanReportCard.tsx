import { useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { ApiError, type Problem } from "../../api/problem.ts";
import {
  fetchReportFileContent,
  REPORT_UPLOAD_LIMITS,
  uploadReportFile,
  type PipelineStatus,
  type ReportFile,
} from "../../api/reportFiles.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { saveFile } from "../shared/saveFile.ts";
import type { ScanReport } from "./scanReports.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/** What a file's status means to the person, in the order the stages run. */
const STATUS_LABELS: Readonly<Record<PipelineStatus, string>> = {
  QUARANTINED: "Received, waiting for the malware scan",
  SCAN_SUBMITTED: "Being scanned for malware",
  SCAN_CLEAN: "Scanned clean, checking the file",
  TYPE_OK: "Type accepted, checking the structure",
  SCHEMA_OK: "Accepted",
  PARSED: "Accepted, findings read",
  REJECTED_MALWARE: "Rejected",
  REJECTED_SANITIZED: "Rejected: modified during scanning",
  REJECTED_SCAN_TIMEOUT: "Rejected",
  REJECTED_TYPE: "Rejected",
  REJECTED_TOO_LARGE: "Rejected",
  REJECTED_UNREADABLE: "Rejected",
  REJECTED_SCHEMA: "Rejected",
  REJECTED_HASH_MISMATCH: "Rejected",
};

function isAccepted(file: ReportFile): boolean {
  return file.status === "SCHEMA_OK" || file.status === "PARSED";
}

function isRejected(file: ReportFile): boolean {
  return file.status.startsWith("REJECTED_");
}

function problemOf(failure: unknown): Problem {
  return failure instanceof ApiError ? failure.problem : UNREACHABLE;
}

function megabytes(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function refusal(title: string, detail: string): Problem {
  return { type: "about:blank", title, status: 400, detail };
}

/**
 * Early feedback only, so a file the server cannot accept is not sent: the server decides every file from its bytes and
 * stays the authority. Returns what to tell the person, or undefined when the file may be sent.
 */
function refusalFor(report: ScanReport, file: File): Problem | undefined {
  const limits = REPORT_UPLOAD_LIMITS[report.type];
  if (file.size === 0) return refusal("A file is empty", "Choose the report your tool exported.");
  if (file.size > limits.maxBytes) {
    return refusal("A file is too large", `A ${report.title} file can be at most ${megabytes(limits.maxBytes)}.`);
  }
  if (!limits.extensions.some((extension) => file.name.toLowerCase().endsWith(extension))) {
    return refusal("A file is not an accepted type", `Choose a ${limits.extensions.join(" or ")} file.`);
  }
  return undefined;
}

/** One file with its pipeline status and, when it was rejected, the server's plain message. */
function FileRow({
  submissionId,
  file,
  onProblem,
}: {
  submissionId: string;
  file: ReportFile;
  onProblem: (problem: Problem) => void;
}) {
  async function save() {
    try {
      saveFile(await fetchReportFileContent(submissionId, file));
    } catch (failure) {
      onProblem(problemOf(failure));
    }
  }

  return (
    <li>
      <strong>{file.name}</strong> <span className="muted">({megabytes(file.sizeBytes)})</span>{" "}
      <span className={isRejected(file) ? "flag flag--danger" : "flag"}>{STATUS_LABELS[file.status]}</span>
      {isAccepted(file) ? (
        <>
          {" "}
          <button type="button" className="button button--secondary" onClick={() => void save()}>
            Download<span className="visually-hidden"> {file.name}</span>
          </button>
        </>
      ) : null}
      {file.message ? <p>{file.message}</p> : null}
    </li>
  );
}

/**
 * Choose files and upload them one after another; each is quarantined at once and checked afterwards. A file that fails
 * stops the rest, and the ones that were sent are not sent again when the person tries once more.
 */
function UploadForm({
  submissionId,
  report,
  classification,
  onUploaded,
  onProblem,
}: {
  submissionId: string;
  report: ScanReport;
  classification: string;
  onUploaded: () => void;
  onProblem: (problem: Problem | undefined) => void;
}) {
  const [chosen, setChosen] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const inputId = `files-${report.type}`;
  const limits = REPORT_UPLOAD_LIMITS[report.type];

  function choose(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    const refused = files.map((file) => refusalFor(report, file)).find((problem) => problem !== undefined);
    onProblem(refused);
    setChosen(refused ? [] : files);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    onProblem(undefined);
    const remaining = [...chosen];
    try {
      for (let next = remaining[0]; next !== undefined; next = remaining[0]) {
        await uploadReportFile(submissionId, report.type, next);
        remaining.shift();
      }
      form.current?.reset();
    } catch (failure) {
      onProblem(problemOf(failure));
    } finally {
      setChosen(remaining);
      setBusy(false);
      onUploaded();
    }
  }

  return (
    <form ref={form} onSubmit={(event) => void submit(event)}>
      <ClassifiedField
        label={`Add files (${limits.extensions.join(", ")}, up to ${megabytes(limits.maxBytes)} each)`}
        classification={classification}
        id={inputId}
      >
        <input id={inputId} type="file" multiple accept={limits.extensions.join(",")} onChange={choose} />
      </ClassifiedField>
      {chosen.length > 0 ? (
        <p className="muted">
          {chosen.length} file{chosen.length === 1 ? "" : "s"} still to upload.
        </p>
      ) : null}
      <p>
        <button type="submit" className="button" disabled={chosen.length === 0 || busy}>
          {busy ? "Uploading…" : "Upload"}
        </button>
      </p>
    </form>
  );
}

/**
 * One Scan Report (stories 20, 24 and 27): its files with their pipeline status and, for a rejected one, the plain
 * message the server gave, and the control to add more files. Several files make one Scan Report.
 */
export function ScanReportCard({
  submissionId,
  report,
  files,
  classification,
  onChanged,
}: {
  submissionId: string;
  report: ScanReport;
  files: readonly ReportFile[];
  classification: string;
  onChanged: () => void;
}) {
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  return (
    <section className="card" aria-labelledby={`report-${report.type}`}>
      <h3 id={`report-${report.type}`}>{report.title}</h3>
      <p className="muted">{report.note}</p>
      {files.length === 0 ? (
        <p>No files uploaded yet.</p>
      ) : (
        <ul className="plain-list" aria-live="polite">
          {files.map((file) => (
            <FileRow key={file.id} submissionId={submissionId} file={file} onProblem={setProblem} />
          ))}
        </ul>
      )}
      {problem ? <ErrorPanel problem={problem} /> : null}
      <UploadForm
        submissionId={submissionId}
        report={report}
        classification={classification}
        onUploaded={onChanged}
        onProblem={setProblem}
      />
    </section>
  );
}
