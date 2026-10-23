import { useCallback, useState, type FormEvent } from "react";
import { dayLabel } from "../../api/cycles.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import {
  confirmReportFileMetadata,
  fetchReportFileMetadata,
  MAX_TARGET_LENGTH,
  type ReportFile,
  type ReportFileMetadata,
} from "../../api/reportFiles.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/** What the file itself says, in words: a Burp Suite export's only date is when it was exported. */
function FromTheFile({ metadata }: { metadata: ReportFileMetadata }) {
  const day = metadata.scanDay === null ? undefined : dayLabel(metadata.scanDay);
  const exported = `exported on ${day ?? "an unknown day"}; Burp Suite records only when it exported the file, not when the scan was made`;
  const when = metadata.scanDateIsExportTime ? exported : day === undefined ? "no scan date" : `scanned on ${day}`;
  return (
    <p className="muted">
      The file says: {when}. Target:{" "}
      {metadata.target === null ? "not named" : <span className="finding-detail">{metadata.target}</span>}.
    </p>
  );
}

/** What the SMO/Tech Lead confirmed, with an edited scan date and an edited target marked for the reviewer. */
function Confirmed({ metadata }: { metadata: ReportFileMetadata }) {
  if (metadata.confirmedScanDate === null || metadata.confirmedTarget === null) {
    return <p>Not confirmed yet.</p>;
  }
  return (
    <p>
      Confirmed: scanned on {dayLabel(metadata.confirmedScanDate)}
      {metadata.scanDateEdited ? (
        <>
          {" "}
          <span className="flag flag--warning">Scan date edited</span>
        </>
      ) : null}
      ; target <span className="finding-detail">{metadata.confirmedTarget}</span>
      {metadata.targetEdited ? (
        <>
          {" "}
          <span className="flag flag--warning">Target edited</span>
        </>
      ) : null}
    </p>
  );
}

/** The two inputs, pre-filled with what was confirmed, else with what the file says. */
function ConfirmForm({
  submissionId,
  file,
  metadata,
  classification,
  onSaved,
}: {
  submissionId: string;
  file: ReportFile;
  metadata: ReportFileMetadata;
  classification: string;
  onSaved: () => void;
}) {
  const [scanDate, setScanDate] = useState(metadata.confirmedScanDate ?? metadata.scanDay ?? "");
  const [target, setTarget] = useState(metadata.confirmedTarget ?? metadata.target ?? "");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const dateId = `scan-date-${file.id}`;
  const targetId = `scan-target-${file.id}`;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setProblem(undefined);
    try {
      await confirmReportFileMetadata(submissionId, file.id, { scanDate, target });
      onSaved();
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)}>
      <ClassifiedField
        label={metadata.scanDateIsExportTime ? "Date the scan was made (not the export date)" : "Scan date"}
        classification={classification}
        id={dateId}
      >
        <input
          id={dateId}
          type="date"
          required
          value={scanDate}
          aria-invalid={problem?.field === "scanDate"}
          onChange={(event) => setScanDate(event.target.value)}
        />
      </ClassifiedField>
      <ClassifiedField label="Target that was scanned" classification={classification} id={targetId}>
        <input
          id={targetId}
          type="text"
          required
          maxLength={MAX_TARGET_LENGTH}
          value={target}
          aria-invalid={problem?.field === "target"}
          onChange={(event) => setTarget(event.target.value)}
        />
      </ClassifiedField>
      {problem ? <ErrorPanel problem={problem} /> : null}
      <p>
        <button type="submit" className="button button--secondary" disabled={busy}>
          {busy ? "Saving…" : "Confirm"}
          <span className="visually-hidden"> the scan date and target of {file.name}</span>
        </button>
      </p>
    </form>
  );
}

/**
 * The scan date and target of one parsed report file (story 25): what the file says, pre-filled for the SMO/Tech Lead to
 * confirm or correct, each input with its classification label (IM8 dp-8). A Burp Suite export records only when it was
 * exported, which is said, and its scan date must be given. A scan date or target that differs from the file's is marked,
 * never refused.
 * The server judges the dates; everything shown is text.
 */
export function ScanDateForm({
  submissionId,
  file,
  classification,
  onConfirmed,
}: {
  submissionId: string;
  file: ReportFile;
  classification: string;
  onConfirmed: () => void;
}) {
  const load = useCallback(() => fetchReportFileMetadata(submissionId, file.id), [submissionId, file.id]);
  const metadata = useResource(load);
  const { data, error } = metadata;
  return (
    <div>
      <h5>{file.name}</h5>
      {error ? <ErrorPanel problem={error.problem} /> : null}
      {data ? (
        <>
          <FromTheFile metadata={data} />
          <Confirmed metadata={data} />
          <ConfirmForm
            key={`${data.confirmedScanDate ?? ""}|${data.confirmedTarget ?? ""}`}
            submissionId={submissionId}
            file={file}
            metadata={data}
            classification={classification}
            onSaved={() => {
              metadata.reload();
              onConfirmed();
            }}
          />
        </>
      ) : error ? null : (
        <p className="muted">Loading the scan date and target…</p>
      )}
    </div>
  );
}
