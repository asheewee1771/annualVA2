import { useState } from "react";
import { ApiError, type Problem } from "../../api/problem.ts";
import { fetchRegisterTemplate } from "../../api/systemRegister.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { saveFile } from "../shared/saveFile.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/**
 * The System Register (story 1): the VA POC downloads the fixed Excel template, fills it in with each System, its
 * SMO/Tech Lead, Cyber Manager and Quarter, and uploads it (the upload arrives in a later ticket).
 */
export function RegisterScreen() {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function downloadTemplate() {
    setBusy(true);
    setProblem(undefined);
    try {
      saveFile(await fetchRegisterTemplate());
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    } finally {
      setBusy(false);
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
        <button type="button" className="button" disabled={busy} onClick={() => void downloadTemplate()}>
          {busy ? "Preparing the template…" : "Download template"}
        </button>
      </p>
      <p className="muted">
        Columns: System ID, System Name, SMO/Tech Lead email, Cyber Manager email, Quarter (Q1 to Q4). The first row is
        an example to delete before you upload.
      </p>
      {problem ? <ErrorPanel problem={problem} /> : null}
    </div>
  );
}
