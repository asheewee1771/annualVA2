import { useCallback, useState, type FormEvent } from "react";
import { fetchSmoInbox, type InboxSystem } from "../../api/dashboards.ts";
import { MAX_EVIDENCE_LENGTH, requestExemption, type ExemptionBasis } from "../../api/exemptions.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { fetchReportFiles } from "../../api/reportFiles.ts";
import { useSelectedCycle } from "../../app/CycleChoice.tsx";
import { AppLink } from "../../app/AppLink.tsx";
import { navigate } from "../../app/router.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { SMO_AREA_PATH } from "./paths.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

const BASES: readonly { basis: ExemptionBasis; label: string }[] = [
  { basis: "PRIOR_VA", label: "A VA done within the 3 months before the Quarter" },
  { basis: "SCHEDULED_VA", label: "A VA scheduled within the 3 months after the Quarter (a conditional Exemption)" },
];

/**
 * The supporting evidence files of the VA Submission that passed every check, which one may be attached; a System
 * with no VA Submission has none, and a file is uploaded on the VA Submission first.
 */
function EvidenceFileChoice({
  submissionId,
  value,
  busy,
  classification,
  onChange,
}: {
  submissionId: string | null;
  value: string;
  busy: boolean;
  classification: string;
  onChange: (fileId: string) => void;
}) {
  const load = useCallback(() => (submissionId ? fetchReportFiles(submissionId) : Promise.resolve([])), [submissionId]);
  const files = useResource(load);
  const ready = (files.data ?? []).filter((file) => file.kind === "EVIDENCE" && file.status === "READY");
  return (
    <ClassifiedField label="Evidence file (optional)" classification={classification} id="exemption-file">
      <select id="exemption-file" value={value} disabled={busy} onChange={(event) => onChange(event.target.value)}>
        <option value="">No file</option>
        {ready.map((file) => (
          <option key={file.id} value={file.id}>
            {file.name}
          </option>
        ))}
      </select>
      <span className="muted">
        A file is one of this VA Submission&apos;s supporting evidence files that has passed every check: upload it on
        the VA Submission first.
      </span>
    </ClassifiedField>
  );
}

/** The form itself, for one System of the caller's. Every check is the server's; its problem names the wrong field. */
function RequestForm({
  system,
  cycle,
  classification,
}: {
  system: InboxSystem;
  cycle: number;
  classification: string;
}) {
  const [basis, setBasis] = useState<ExemptionBasis>("PRIOR_VA");
  const [vaDate, setVaDate] = useState("");
  const [evidence, setEvidence] = useState("");
  const [fileId, setFileId] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(undefined);
    try {
      await requestExemption(system.id, {
        basis,
        vaDate,
        evidence,
        cycle,
        ...(fileId ? { evidenceFileId: fileId } : {}),
      });
      navigate(SMO_AREA_PATH);
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} aria-describedby={problem ? "exemption-problem" : undefined}>
      <ClassifiedField label="What the Exemption rests on" classification={classification} id="exemption-basis">
        <select
          id="exemption-basis"
          value={basis}
          disabled={busy}
          aria-invalid={problem?.field === "basis"}
          onChange={(event) => setBasis(event.target.value === "SCHEDULED_VA" ? "SCHEDULED_VA" : "PRIOR_VA")}
        >
          {BASES.map((option) => (
            <option key={option.basis} value={option.basis}>
              {option.label}
            </option>
          ))}
        </select>
      </ClassifiedField>
      <ClassifiedField label="Date of the VA" classification={classification} id="exemption-va-date">
        <input
          id="exemption-va-date"
          type="date"
          required
          value={vaDate}
          disabled={busy}
          aria-invalid={problem?.field === "vaDate"}
          onChange={(event) => setVaDate(event.target.value)}
        />
      </ClassifiedField>
      <ClassifiedField
        label="Evidence: who did the VA, its report or booking reference"
        classification={classification}
        id="exemption-evidence"
      >
        <textarea
          id="exemption-evidence"
          rows={4}
          required
          maxLength={MAX_EVIDENCE_LENGTH}
          value={evidence}
          disabled={busy}
          aria-invalid={problem?.field === "evidence"}
          onChange={(event) => setEvidence(event.target.value)}
        />
      </ClassifiedField>
      <EvidenceFileChoice
        submissionId={system.state === "NOT_STARTED" ? null : system.submissionId}
        value={fileId}
        busy={busy}
        classification={classification}
        onChange={setFileId}
      />
      {problem ? (
        <div id="exemption-problem">
          <ErrorPanel problem={problem} />
        </div>
      ) : null}
      <p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? "Requesting…" : "Request the Exemption"}
        </button>
      </p>
    </form>
  );
}

/**
 * Where the SMO/Tech Lead requests an Exemption for one of their Systems (stories 43 and 44): what it rests on, the VA's
 * date, the evidence and optionally a supporting evidence file. The VA Submission is frozen once it is requested, and a
 * System with none gets one. The System and its state are the inbox's; a System that cannot take a request is refused by
 * the server.
 */
export function ExemptionRequestScreen({ systemId, classification }: { systemId: string; classification: string }) {
  const cycle = useSelectedCycle();
  const load = useCallback(() => fetchSmoInbox(cycle), [cycle]);
  const inbox = useResource(load);
  const system = inbox.data?.systems.find((candidate) => candidate.id === systemId);

  return (
    <div className="card">
      <p>
        <AppLink path={SMO_AREA_PATH}>Back to your Systems</AppLink>
      </p>
      <h2>Request an Exemption</h2>
      {inbox.error ? <ErrorPanel problem={inbox.error.problem} /> : null}
      {inbox.data && !system ? <p>This System is not one of yours in this VA Cycle.</p> : null}
      {inbox.data && system ? (
        <>
          <p className="muted">
            <span className="mono">{system.systemId}</span> {system.name}, VA Cycle {inbox.data.cycle}, Q
            {system.quarter}: {STATE_LABELS[system.state]}. While a VA POC decides, the VA Submission is frozen: nothing
            can be changed or submitted. A rejection puts it back exactly as it was.
          </p>
          <RequestForm system={system} cycle={inbox.data.cycle} classification={classification} />
        </>
      ) : null}
    </div>
  );
}
