import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { ApiError, type Problem } from "../../api/problem.ts";
import {
  deleteReportFile,
  EVIDENCE_UPLOAD_LIMITS,
  fetchReportFileContent,
  REPORT_UPLOAD_LIMITS,
  replaceReportFile,
  uploadReportFile,
  type FileKind,
  type PipelineStatus,
  type ReportFile,
} from "../../api/reportFiles.ts";
import type { ScanReportMode } from "../../api/scanReports.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { saveFile } from "../shared/saveFile.ts";
import { ConfirmDelete } from "./ConfirmDelete.tsx";
import { Editable } from "./Editable.tsx";
import { ManualFindingForm } from "./ManualFindingForm.tsx";
import { ScanDateForm } from "./ScanDateForm.tsx";
import { ScanReportModeForm } from "./ScanReportModeForm.tsx";
import { inProgress, type ScanReport } from "../shared/scanReports.ts";

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
  REJECTED_FUTURE_DATED: "Rejected: dated in the future",
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

/** A replaced file says so, and says when its content has been disposed of (its record is kept, ticket 49). */
function ReplacedFlags({ file, files }: { file: ReportFile; files: readonly ReportFile[] }) {
  if (file.supersededBy === null) return null;
  return (
    <>
      {" "}
      <span className="flag">
        {hasLiveRescan(file, files) ? "Replaced by a rescan" : "Replaced by a rescan that was deleted"}
      </span>
      {file.contentDisposedAt === null ? null : (
        <>
          {" "}
          <span className="flag">Content disposed of</span>
        </>
      )}
    </>
  );
}

/**
 * Whether the rescan that replaced this file is still there. The list leaves out deleted files, so a replaced file whose
 * rescan is not in it had that rescan deleted (ticket 60).
 */
function hasLiveRescan(file: ReportFile, files: readonly ReportFile[]): boolean {
  return file.supersededBy !== null && files.some((other) => other.id === file.supersededBy);
}

/**
 * A report file that can be deleted (ticket 60), in any status, still being checked, rejected or a parsed rescan
 * included: any one but supporting evidence and a file a rescan that is still there has replaced. The server decides; this
 * only offers the control where it can succeed.
 */
function isDeletable(file: ReportFile, files: readonly ReportFile[]): boolean {
  return file.kind === "MACHINE_READABLE" && !hasLiveRescan(file, files);
}

/** A file whose content is held and may be downloaded: it passed every check and has not been disposed of. */
function isDownloadable(file: ReportFile): boolean {
  return isAccepted(file) && file.contentDisposedAt === null;
}

/**
 * The controls that change a file, in one {@link Editable} group: Replace for a report file that can be replaced with a
 * rescan, and Delete for one uploaded by mistake (ticket 60); nothing when neither applies.
 */
function FileChanges({
  submissionId,
  file,
  files,
  onReplace,
  onDeleted,
  onProblem,
}: {
  submissionId: string;
  file: ReportFile;
  files: readonly ReportFile[];
  onReplace: (file: ReportFile) => void;
  onDeleted: (deleted: boolean) => void;
  onProblem: (problem: Problem | undefined) => void;
}) {
  const replaceable = isReplaceable(file, files);
  const deletable = isDeletable(file, files);
  if (!replaceable && !deletable) return null;

  async function remove(): Promise<boolean> {
    onProblem(undefined);
    let deleted = false;
    try {
      await deleteReportFile(submissionId, file.id);
      deleted = true;
    } catch (failure) {
      onProblem(problemOf(failure));
    }
    onDeleted(deleted);
    return deleted;
  }

  return (
    <>
      {" "}
      <Editable>
        {replaceable ? (
          <button type="button" className="button button--secondary" onClick={() => onReplace(file)}>
            Replace<span className="visually-hidden"> {file.name}</span>
          </button>
        ) : null}
        {deletable ? (
          <ConfirmDelete
            name={file.name}
            what="file"
            consequence="Delete this file? A Finding no other file reports is removed if it never had a status; one that had a status stays and is proposed Remediated."
            onDelete={remove}
          />
        ) : null}
      </Editable>
    </>
  );
}

/**
 * One file with its pipeline status and, when it was rejected, the server's plain message; a rescan names the file it
 * replaces, a replaced file says so, a report file that can be replaced offers Replace, and one uploaded by mistake can be
 * deleted.
 */
function FileRow({
  submissionId,
  report,
  file,
  files,
  onReplace,
  onDeleted,
  onProblem,
}: {
  submissionId: string;
  report: ScanReport;
  file: ReportFile;
  files: readonly ReportFile[];
  onReplace: (file: ReportFile) => void;
  /** The deletion was answered: whether the file was deleted. The files are read again either way. */
  onDeleted: (deleted: boolean) => void;
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
      <ReplacedFlags file={file} files={files} />
      {isDownloadable(file) ? (
        <>
          {" "}
          <button type="button" className="button button--secondary" onClick={() => void save()}>
            Download<span className="visually-hidden"> {file.name}</span>
          </button>
        </>
      ) : null}
      <FileChanges
        submissionId={submissionId}
        file={file}
        files={files}
        onReplace={onReplace}
        onDeleted={onDeleted}
        onProblem={onProblem}
      />
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

/** A report file whose scan date and target are confirmed: its findings were read and no rescan has replaced it. */
function isConfirmable(file: ReportFile): boolean {
  return file.kind === "MACHINE_READABLE" && file.status === "PARSED" && file.supersededBy === null;
}

/** The scan date and target of each report file whose findings were read, disabled once the VA Submission is submitted. */
function ScanDates({
  submissionId,
  files,
  classification,
  onConfirmed,
}: {
  submissionId: string;
  files: readonly ReportFile[];
  classification: string;
  onConfirmed: () => void;
}) {
  if (files.length === 0) return null;
  return (
    <>
      <h4>Scan dates and targets</h4>
      <p className="muted">
        Confirm when each scan was made and what it scanned, or correct what the file says. A file more than 90 days old
        cannot be submitted, and a changed target is marked for the Cyber Manager.
      </p>
      <Editable>
        {files.map((file) => (
          <ScanDateForm
            key={file.id}
            submissionId={submissionId}
            file={file}
            classification={classification}
            onConfirmed={onConfirmed}
          />
        ))}
      </Editable>
    </>
  );
}

/** The files of one kind, each with its status, or a line saying there are none yet. */
function FileList({
  submissionId,
  report,
  evidence,
  files,
  onReplace,
  onDeleted,
  onProblem,
}: {
  submissionId: string;
  report: ScanReport;
  evidence: boolean;
  files: readonly ReportFile[];
  onReplace: (file: ReportFile) => void;
  onDeleted: (deleted: boolean) => void;
  onProblem: (problem: Problem | undefined) => void;
}) {
  if (files.length === 0) {
    return <p>{evidence ? "No supporting evidence attached yet." : "No files uploaded yet."}</p>;
  }
  return (
    <ul className="plain-list" aria-live="polite">
      {files.map((file) => (
        <FileRow
          key={file.id}
          submissionId={submissionId}
          report={report}
          file={file}
          files={files}
          onReplace={onReplace}
          onDeleted={onDeleted}
          onProblem={onProblem}
        />
      ))}
    </ul>
  );
}

/**
 * The files of one kind, each with its status, and the control to add more; a problem shows beside the control. Below the
 * report files (outside their live region), the scan date and target of each one whose findings were read.
 */
function FileSection({
  submissionId,
  report,
  kind,
  files,
  classification,
  onChanged,
  onScanDateConfirmed,
}: {
  submissionId: string;
  report: ScanReport;
  kind: FileKind;
  files: readonly ReportFile[];
  classification: string;
  onChanged: () => void;
  onScanDateConfirmed: () => void;
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
      <FileList
        submissionId={submissionId}
        report={report}
        evidence={evidence}
        files={files}
        onReplace={(file) => setReplacingId(file.id)}
        onDeleted={(deleted) => {
          // A deleted file's row and its buttons go: the focus goes back to the files' heading. A refused deletion keeps
          // the row, its Delete button takes the focus back, and the problem shows beside it.
          if (deleted) heading.current?.focus();
          onChanged();
        }}
        onProblem={setProblem}
      />
      {replacing ? (
        <Editable>
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
        </Editable>
      ) : null}
      {problem ? <ErrorPanel problem={problem} /> : null}
      <ScanDates
        submissionId={submissionId}
        files={files.filter(isConfirmable)}
        classification={classification}
        onConfirmed={onScanDateConfirmed}
      />
      <Editable>
        <UploadForm
          submissionId={submissionId}
          report={report}
          kind={kind}
          classification={classification}
          onUploaded={onChanged}
          onProblem={setProblem}
        />
      </Editable>
    </>
  );
}

/**
 * One Scan Report (stories 20, 22, 23, 24, 25, 27 and 39): how it is provided, then its machine-readable files when it is
 * uploaded, the form to enter a Finding by hand when its tool cannot export one, and, in every mode, its supporting
 * evidence. Each file shows its pipeline status and, for a rejected one, the plain message the server gave, with the
 * control to add more; a report file whose findings were read can be replaced with a rescan, and its scan date and target
 * are confirmed or corrected.
 * Several files make one Scan Report. Evidence is never shown in the page: it is only offered as a download.
 */
export function ScanReportCard({
  submissionId,
  report,
  mode,
  files,
  classification,
  onChanged,
  onModeChanged,
  onFindingEntered,
  onScanDateConfirmed,
}: {
  submissionId: string;
  report: ScanReport;
  /** How it is provided; undefined until the server has said. */
  mode: ScanReportMode | undefined;
  files: readonly ReportFile[];
  classification: string;
  onChanged: () => void;
  onModeChanged: () => void;
  onFindingEntered: () => void;
  /** A scan date and target were confirmed: the age of the files is asked for again. */
  onScanDateConfirmed: () => void;
}) {
  // Report files are offered unless the Scan Report is known to be not applicable or entered by hand.
  const kinds: readonly FileKind[] = mode && mode.mode !== "UPLOAD" ? ["EVIDENCE"] : ["MACHINE_READABLE", "EVIDENCE"];
  return (
    <section className="card" aria-labelledby={`report-${report.type}`}>
      <h3 id={`report-${report.type}`}>{report.title}</h3>
      <p className="muted">{report.note}</p>
      {mode ? (
        <Editable>
          <ScanReportModeForm
            key={`${mode.mode}|${mode.justification ?? ""}`}
            submissionId={submissionId}
            report={report}
            current={mode}
            classification={classification}
            onChanged={onModeChanged}
          />
        </Editable>
      ) : null}
      {mode?.mode === "MANUAL" ? (
        <Editable>
          <ManualFindingForm
            submissionId={submissionId}
            report={report}
            classification={classification}
            onEntered={onFindingEntered}
          />
        </Editable>
      ) : null}
      {kinds.map((kind) => (
        <FileSection
          key={kind}
          submissionId={submissionId}
          report={report}
          kind={kind}
          files={files.filter((file) => file.kind === kind)}
          classification={classification}
          onChanged={onChanged}
          onScanDateConfirmed={onScanDateConfirmed}
        />
      ))}
    </section>
  );
}
