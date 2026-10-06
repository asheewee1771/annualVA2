import { useCallback, useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { ApiError, type Problem } from "../../api/problem.ts";
import { takeRestoredDraft } from "../../api/stepUp.ts";
import {
  applyRegisterPreview,
  fetchRegisterPreview,
  fetchRegisterTemplate,
  fetchSystems,
  uploadRegister,
  type AppliedRegister,
  type RegisterPreview,
} from "../../api/systemRegister.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { saveFile } from "../shared/saveFile.ts";
import { RegisterPreviewPanel } from "./RegisterPreviewPanel.tsx";
import { RegisterTable } from "./RegisterTable.tsx";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };
/** The server's own limit; checked here too so a file that is too large is not sent at all. */
const MAX_BYTES = 2 * 1024 * 1024;

function refusal(title: string, detail: string): Problem {
  return { type: "about:blank", title, status: 400, detail };
}

const EMPTY_FILE = refusal("The file is empty", "Choose the filled-in template.");
const TOO_LARGE = refusal(
  "The file is larger than 2 MB",
  "Choose the filled-in template, which is far smaller than that.",
);
const NOT_XLSX = refusal("Only an Excel .xlsx workbook is accepted", "Choose the filled-in template, saved as .xlsx.");

/**
 * Early feedback only, so an obviously wrong file is not sent: the server decides every file from its bytes and stays
 * the authority. Returns what to tell the person, or undefined when the file may be sent.
 */
function refusalFor(file: File): Problem | undefined {
  if (file.size === 0) return EMPTY_FILE;
  if (file.size > MAX_BYTES) return TOO_LARGE;
  if (!file.name.toLowerCase().endsWith(".xlsx")) return NOT_XLSX;
  return undefined;
}

function problemOf(failure: unknown): Problem {
  return failure instanceof ApiError ? failure.problem : UNREACHABLE;
}

/** The System Register template, saved to the browser's own downloads. */
function TemplateDownload({ onProblem }: { onProblem: (problem: Problem | undefined) => void }) {
  const [busy, setBusy] = useState(false);

  async function downloadTemplate() {
    setBusy(true);
    onProblem(undefined);
    try {
      saveFile(await fetchRegisterTemplate());
    } catch (failure) {
      onProblem(problemOf(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <button type="button" className="button" disabled={busy} onClick={() => void downloadTemplate()}>
      {busy ? "Preparing the template…" : "Download template"}
    </button>
  );
}

interface UploadFormProps {
  classification: string;
  onPreview: (preview: RegisterPreview | undefined) => void;
  onProblem: (problem: Problem | undefined) => void;
}

/** Choose the filled-in template and ask for a preview; nothing in the Register changes (story 2). */
function UploadForm({ classification, onPreview, onProblem }: UploadFormProps) {
  const [file, setFile] = useState<File | undefined>(undefined);
  const [busy, setBusy] = useState(false);

  function choose(event: ChangeEvent<HTMLInputElement>) {
    setFile(event.target.files?.[0]);
    onPreview(undefined);
    onProblem(undefined);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) return;
    onPreview(undefined);
    const refused = refusalFor(file);
    if (refused) {
      onProblem(refused);
      return;
    }
    setBusy(true);
    onProblem(undefined);
    try {
      onPreview(await uploadRegister(file));
    } catch (failure) {
      onProblem(problemOf(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)}>
      <ClassifiedField
        label="Filled-in template (.xlsx, up to 2 MB)"
        classification={classification}
        id="register-file"
      >
        <input
          id="register-file"
          type="file"
          accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          onChange={choose}
        />
      </ClassifiedField>
      <p>
        <button type="submit" className="button" disabled={!file || busy}>
          {busy ? "Reading the file…" : "Upload and preview"}
        </button>
      </p>
    </form>
  );
}

/** What an apply did, in one sentence. */
function AppliedNotice({ applied }: { applied: AppliedRegister }) {
  return (
    <div className="notice" role="status">
      <p>
        <strong>The Register is updated.</strong> {applied.added} added, {applied.updated} updated, {applied.retired}{" "}
        retired, {applied.unchanged} unchanged. {applied.rolesGranted} role{applied.rolesGranted === 1 ? "" : "s"}{" "}
        granted and {applied.rolesRevoked} revoked.
      </p>
    </div>
  );
}

function isStepUpRequired(problem: Problem): boolean {
  return problem.type.endsWith("/step-up-required");
}

/** The preview a person was about to apply when they left for a fresh sign-in; reopened when they come back. */
function previewIdFrom(draft: unknown): string | undefined {
  return typeof draft === "object" && draft !== null && "previewId" in draft && typeof draft.previewId === "string"
    ? draft.previewId
    : undefined;
}

/**
 * Read once as the page loads (a step-up round trip is a full page load) and forgotten once the preview is open
 * again, so going to another screen and back never reopens a preview.
 */
let leftForSignIn = previewIdFrom(takeRestoredDraft("register-apply"));

/** Opens the preview a person left for the step-up sign-in, if they left one. */
function useReopenedPreview(
  onPreview: (preview: RegisterPreview) => void,
  onProblem: (problem: Problem) => void,
): void {
  const [id] = useState(leftForSignIn);
  useEffect(() => {
    if (id === undefined) return;
    let cancelled = false;
    fetchRegisterPreview(id).then(
      (preview) => {
        if (cancelled) return;
        leftForSignIn = undefined;
        onPreview(preview);
      },
      (failure: unknown) => {
        if (cancelled) return;
        leftForSignIn = undefined;
        onProblem(problemOf(failure));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [id, onPreview, onProblem]);
}

/**
 * The System Register (stories 1 to 4, 12, 73 and 79): the VA POC downloads the fixed Excel template, fills it in with
 * each System, its SMO/Tech Lead, Cyber Manager and Quarter, uploads it and sees a preview of every change before
 * anything changes, then applies it with a fresh sign-in. The table beneath is the Register as it stands.
 */
export function RegisterScreen({ classification }: { classification: string }) {
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const [preview, setPreview] = useState<RegisterPreview | undefined>(undefined);
  const [applied, setApplied] = useState<AppliedRegister | undefined>(undefined);
  const [applying, setApplying] = useState(false);
  const systems = useResource(fetchSystems);

  const showPreview = useCallback((next: RegisterPreview | undefined) => {
    setPreview(next);
    setApplied(undefined);
  }, []);
  useReopenedPreview(showPreview, setProblem);

  async function apply() {
    if (!preview) return;
    setApplying(true);
    setProblem(undefined);
    try {
      setApplied(await applyRegisterPreview(preview.id));
      setPreview(undefined);
      systems.reload();
    } catch (failure) {
      const refused = problemOf(failure);
      // A step-up refusal is explained by the sign-in dialog; the preview is kept so the person can try again.
      if (!isStepUpRequired(refused)) setProblem(refused);
      // A preview that was applied, expired or made against an older Register is of no use any more.
      if (refused.status === 409) setPreview(undefined);
    } finally {
      setApplying(false);
    }
  }

  return (
    <div className="card">
      <h2>System Register</h2>
      <p className="muted">
        The Register lists every in-scope System with its SMO/Tech Lead, Cyber Manager and Quarter. Download the
        template, fill in one row per System and keep each System ID the same on every upload.
      </p>
      <p>
        <TemplateDownload onProblem={setProblem} />
      </p>
      <p className="muted">
        Columns: System ID, System Name, SMO/Tech Lead email, Cyber Manager email, Quarter (Q1 to Q4). The first row is
        an example to delete before you upload.
      </p>
      <h3>Upload the filled-in template</h3>
      <UploadForm classification={classification} onPreview={showPreview} onProblem={setProblem} />
      {problem ? <ErrorPanel problem={problem} /> : null}
      {applied ? <AppliedNotice applied={applied} /> : null}
      {preview ? <RegisterPreviewPanel preview={preview} applying={applying} onApply={() => void apply()} /> : null}
      <h3>Systems in the Register</h3>
      {systems.error ? <ErrorPanel problem={systems.error.problem} /> : null}
      {systems.data ? <RegisterTable systems={systems.data} /> : null}
      {systems.loading && !systems.data ? <p className="muted">Loading the Register…</p> : null}
    </div>
  );
}
