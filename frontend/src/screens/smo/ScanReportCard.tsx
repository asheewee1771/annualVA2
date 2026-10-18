import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { ApiError, type Problem } from "../../api/problem.ts";
import {
  EVIDENCE_UPLOAD_LIMITS,
  fetchReportFileContent,
  REPORT_UPLOAD_LIMITS,
  replaceReportFile,
  uploadReportFile,
  type FileKind,
  type PipelineStatus,
  type ReportFile,
} from "../../api/reportFiles.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { saveFile } from "../shared/saveFile.ts";
import { inProgress, type ScanReport } from "./scanReports.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/** What a file's status means to the person, in the order the stages run. */
const STATUS_LABELS: Readonly<Record<PipelineStatus, string>> = {
  QUARANTINED: "Received, waiting for the malware scan",
  SCAN_SUBMITTED: "Being scanned for malware",
  SCAN_CLEAN: "Scanned clean, checking the file",
  TYPE_OK: "Type accepted, checking the structure",
  SCHEMA_OK: "Accepted",
  PARSED: "Accepted, findings read",
  READY: "Accepted as supporting evidence",
  REJECTED_MALWARE: "Rejected",
  REJECTED_SANITIZED: "Rejected: modified during scanning",
  REJECTED_SCAN_TIMEOUT: "Rejected",
  REJECTED_TYPE: "Rejected",
  REJECTED_TOO_LARGE: "Rejected",
  REJECTED_TOO_MANY_FINDINGS: "Rejected: too many findings",
  REJECTED_UNREADABLE: "Rejected",
  REJECTED_SCHEMA: "Rejected",
  REJECTED_HASH_MISMATCH: "Rejected",
  REJECTED_ENCRYPTED: "Rejected: password protected",
};

function isAccepted(file: ReportFile): boolean {
  return file.status === "SCHEMA_OK" || file.status === "PARSED" || file.status === "READY";
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

function hasExtension(file: File, extensions: readonly string[]): boolean {
  return extensions.some((extension) => file.name.toLowerCase().endsWith(extension));
}

/** Early feedback for a supporting evidence file: a PDF or an HTML report, each with its own limit. */
function evidenceRefusalFor(file: File): Problem | undefined {
  const limit = EVIDENCE_UPLOAD_LIMITS.find((each) => hasExtension(file, each.extensions));
  // The browser reports no type for a file it cannot name, so an empty one is let through; one that is named must agree with
  // the extension (a page called .pdf, or a script called .html, is not sent). The server decides from the bytes either way.
  if (!limit || (file.type !== "" && file.type !== limit.mimeType))
    return refusal("A file is not an accepted type", "Attach a PDF or an HTML report (.pdf, .html or .htm).");
  if (file.size > limit.maxBytes) {
    return refusal("A file is too large", `A ${limit.label} file can be at most ${megabytes(limit.maxBytes)}.`);
  }
  return undefined;
}

/**
 * Early feedback only, so a file the server cannot accept is not sent: the server decides every file from its bytes and
 * stays the authority. Returns what to tell the person, or undefined when the file may be sent.
 */
function refusalFor(report: ScanReport, kind: FileKind, file: File): Problem | undefined {
  if (file.size === 0) return refusal("A file is empty", "Choose the report your tool exported.");
  if (kind === "EVIDENCE") return evidenceRefusalFor(file);
  const limits = REPORT_UPLOAD_LIMITS[report.type];
  if (file.size > limits.maxBytes) {
    return refusal(
      "A file is too large",
      `A ${report.title} file can be at most ${megabytes(limits.maxBytes)}.` +
        (report.tooLargeHint ? ` ${report.tooLargeHint}` : ""),
    );
  }
  if (!hasExtension(file, limits.extensions)) {
    return refusal("A file is not an accepted type", `Choose a ${limits.extensions.join(" or ")} file.`);
  }
  return undefined;
}

/**
 * A report file that can be replaced with a rescan: one whose findings were read, that no rescan has replaced and that has
 * no rescan still on its way through the checks. The server decides; this only offers the control where it can succeed.
 */
function isReplaceable(file: ReportFile, files: readonly ReportFile[]): boolean {
  return (
    file.kind === "MACHINE_READABLE" &&
    file.status === "PARSED" &&
    file.supersededBy === null &&
    !files.some((other) => other.replacesId === file.id && inProgress(other))
  );
}

/**
 * Replace one report file with a rescan of it (story 39). The rescan is checked like any upload; the file and its findings
 * stay as they are until the rescan's findings are read, and a rescan that is rejected changes nothing. It is rendered
 * below the list of files, not inside its live region, and takes the focus when it opens.
 */
function ReplaceForm({
  submissionId,
  report,
  file,
  classification,
  onClose,
  onReplaced,
  onProblem,
}: {
  submissionId: string;
  report: ScanReport;
  file: ReportFile;
  classification: string;
  onClose: () => void;
  onReplaced: () => void;
  onProblem: (problem: Problem | undefined) => void;
}) {
  const [chosen, setChosen] = useState<File | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const inputId = `replace-${file.id}`;
  const limits = REPORT_UPLOAD_LIMITS[report.type];

  useEffect(() => {
    input.current?.focus();
  }, [file.id]);

  function choose(event: ChangeEvent<HTMLInputElement>) {
    const next = event.target.files?.[0];
    const refused = next ? refusalFor(report, "MACHINE_READABLE", next) : undefined;
    onProblem(refused);
    setChosen(refused ? undefined : next);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!chosen) return;
    setBusy(true);
    onProblem(undefined);
    try {
      await replaceReportFile(submissionId, file.id, chosen);
      onClose();
    } catch (failure) {
      onProblem(problemOf(failure));
    } finally {
      setBusy(false);
      onReplaced();
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)}>
      <p className="muted">
        The rescan is checked like any upload. Once its findings are read, the findings it still reports keep their
        status, new ones are added, and any it no longer reports in any file are proposed Remediated for the Cyber
        Manager to review. This file is kept, marked as replaced.
      </p>
      <ClassifiedField
        label={`Rescan of ${file.name} (${limits.extensions.join(", ")}, up to ${megabytes(limits.maxBytes)})`}
        classification={classification}
        id={inputId}
      >
        <input ref={input} id={inputId} type="file" accept={limits.extensions.join(",")} onChange={choose} />
      </ClassifiedField>
      <p>
        <button type="submit" className="button" disabled={!chosen || busy}>
          {busy ? "Uploading…" : "Replace"}
        </button>{" "}
        <button type="button" className="button button--secondary" onClick={onClose} disabled={busy}>
          Cancel
        </button>
      </p>
    </form>
  );
}

/**
 * One file with its pipeline status and, when it was rejected, the server's plain message; a rescan names the file it
 * replaces, a replaced file says so, and a report file that can be replaced offers Replace.
 */
function FileRow({
  submissionId,
  report,
  file,
  files,
  onReplace,
  onProblem,
}: {
  submissionId: string;
  report: ScanReport;
  file: ReportFile;
  files: readonly ReportFile[];
  onReplace: (file: ReportFile) => void;
  onProblem: (problem: Problem | undefined) => void;
}) {
  const replaced = file.replacesId === null ? undefined : files.find((other) => other.id === file.replacesId);
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
      {file.supersededBy !== null ? (
        <>
          {" "}
          <span className="flag">Replaced by a rescan</span>
        </>
      ) : null}
      {isAccepted(file) ? (
        <>
          {" "}
          <button type="button" className="button button--secondary" onClick={() => void save()}>
            Download<span className="visually-hidden"> {file.name}</span>
          </button>
        </>
      ) : null}
      {isReplaceable(file, files) ? (
        <>
          {" "}
          <button type="button" className="button button--secondary" onClick={() => onReplace(file)}>
            Replace<span className="visually-hidden"> {file.name}</span>
          </button>
        </>
      ) : null}
      {replaced ? <p className="muted">Rescan of {replaced.name}</p> : null}
      {file.message ? <p>{file.message}</p> : null}
      {file.status === "REJECTED_TOO_LARGE" && file.kind === "MACHINE_READABLE" && report.tooLargeHint ? (
        <p>{report.tooLargeHint}</p>
      ) : null}
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
  kind,
  classification,
  onUploaded,
  onProblem,
}: {
  submissionId: string;
  report: ScanReport;
  kind: FileKind;
  classification: string;
  onUploaded: () => void;
  onProblem: (problem: Problem | undefined) => void;
}) {
  const [chosen, setChosen] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const inputId = `files-${report.type}-${kind}`;
  const limits = REPORT_UPLOAD_LIMITS[report.type];
  const evidence = kind === "EVIDENCE";
  const label = evidence
    ? `Attach evidence (${EVIDENCE_UPLOAD_LIMITS.map((each) => `${each.label} up to ${megabytes(each.maxBytes)}`).join(", ")})`
    : `Add files (${limits.extensions.join(", ")}, up to ${megabytes(limits.maxBytes)} each)`;
  const accept = (
    evidence ? EVIDENCE_UPLOAD_LIMITS.flatMap((each) => [...each.extensions, each.mimeType]) : limits.extensions
  ).join(",");

  function choose(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    const refused = files.map((file) => refusalFor(report, kind, file)).find((problem) => problem !== undefined);
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
        await uploadReportFile(submissionId, report.type, kind, next);
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
      <ClassifiedField label={label} classification={classification} id={inputId}>
        <input id={inputId} type="file" multiple accept={accept} onChange={choose} />
      </ClassifiedField>
      {chosen.length > 0 ? (
        <p className="muted">
          {chosen.length} file{chosen.length === 1 ? "" : "s"} still to upload.
        </p>
      ) : null}
      <p>
        <button type="submit" className="button" disabled={chosen.length === 0 || busy}>
          {busy ? "Uploading…" : evidence ? "Attach" : "Upload"}
        </button>
      </p>
    </form>
  );
}

/** The files of one kind, each with its status, and the control to add more; a problem shows beside the control. */
function FileSection({
  submissionId,
  report,
  kind,
  files,
  classification,
  onChanged,
}: {
  submissionId: string;
  report: ScanReport;
  kind: FileKind;
  files: readonly ReportFile[];
  classification: string;
  onChanged: () => void;
}) {
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const [replacingId, setReplacingId] = useState<string | undefined>(undefined);
  // When the Replace form closes (sent or cancelled), the focus goes back to the files, whose list it was opened from.
  const heading = useRef<HTMLHeadingElement>(null);
  // Read from the latest list, so a file that can no longer be replaced closes its form.
  const replacing = files.find((file) => file.id === replacingId && isReplaceable(file, files));
  const evidence = kind === "EVIDENCE";
  return (
    <>
      <h4 ref={heading} tabIndex={-1}>
        {evidence ? "Supporting evidence" : "Report files"}
      </h4>
      {evidence ? (
        <p className="muted">
          PDF or HTML reports for people to read. They are scanned for malware but never read for findings, and an HTML
          file is never shown here: it downloads as a file, so open it only if you trust it.
        </p>
      ) : null}
      {files.length === 0 ? (
        <p>{evidence ? "No supporting evidence attached yet." : "No files uploaded yet."}</p>
      ) : (
        <ul className="plain-list" aria-live="polite">
          {files.map((file) => (
            <FileRow
              key={file.id}
              submissionId={submissionId}
              report={report}
              file={file}
              files={files}
              onReplace={(file) => setReplacingId(file.id)}
              onProblem={setProblem}
            />
          ))}
        </ul>
      )}
      {replacing ? (
        <ReplaceForm
          key={replacing.id}
          submissionId={submissionId}
          report={report}
          file={replacing}
          classification={classification}
          onClose={() => {
            setReplacingId(undefined);
            heading.current?.focus();
          }}
          onReplaced={onChanged}
          onProblem={setProblem}
        />
      ) : null}
      {problem ? <ErrorPanel problem={problem} /> : null}
      <UploadForm
        submissionId={submissionId}
        report={report}
        kind={kind}
        classification={classification}
        onUploaded={onChanged}
        onProblem={setProblem}
      />
    </>
  );
}

/**
 * One Scan Report (stories 20, 22, 24, 27 and 39): its machine-readable files and, apart from them, its supporting evidence,
 * each with its pipeline status and, for a rejected one, the plain message the server gave, and the control to add more;
 * a report file whose findings were read can be replaced with a rescan.
 * Several files make one Scan Report. Evidence is never shown in the page: it is only offered as a download.
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
  return (
    <section className="card" aria-labelledby={`report-${report.type}`}>
      <h3 id={`report-${report.type}`}>{report.title}</h3>
      <p className="muted">{report.note}</p>
      {(["MACHINE_READABLE", "EVIDENCE"] as const).map((kind) => (
        <FileSection
          key={kind}
          submissionId={submissionId}
          report={report}
          kind={kind}
          files={files.filter((file) => file.kind === kind)}
          classification={classification}
          onChanged={onChanged}
        />
      ))}
    </section>
  );
}
