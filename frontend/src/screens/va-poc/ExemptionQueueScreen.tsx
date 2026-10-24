import { useCallback, useState } from "react";
import { dayLabel } from "../../api/cycles.ts";
import { decideExemption, fetchExemptionQueue, type QueuedExemption } from "../../api/exemptions.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { fetchReportFileContent } from "../../api/reportFiles.ts";
import { useSelectedCycle } from "../../app/CycleChoice.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { saveFile } from "../shared/saveFile.ts";
import { STATE_LABELS } from "../shared/systemStates.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

const BASIS_LABELS = {
  PRIOR_VA: "VA done before the Quarter",
  SCHEDULED_VA: "VA scheduled after the Quarter (conditional)",
} as const;

const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });

/** The evidence file, saved through the browser and never shown in the page (an HTML file is active content). */
function EvidenceFile({ request, onProblem }: { request: QueuedExemption; onProblem: (problem: Problem) => void }) {
  const fileId = request.evidenceFileId;
  if (!fileId) return null;
  async function save(id: string) {
    try {
      saveFile(await fetchReportFileContent(request.submissionId, { id, name: "evidence" }));
    } catch (failure) {
      onProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    }
  }
  return (
    <p>
      <button type="button" className="button button--secondary" onClick={() => void save(fileId)}>
        Download the evidence file<span className="visually-hidden"> for {request.systemCode}</span>
      </button>
    </p>
  );
}

/** One request with Approve and Reject; where segregation of duties refuses this VA POC, the other one decides. */
function QueueRow({
  request,
  busy,
  onDecide,
  onProblem,
}: {
  request: QueuedExemption;
  busy: boolean;
  onDecide: (request: QueuedExemption, decision: "approve" | "reject") => void;
  onProblem: (problem: Problem) => void;
}) {
  return (
    <tr>
      <td>
        <span className="mono">{request.systemCode}</span> {request.systemName}
      </td>
      <td>Q{request.quarter}</td>
      <td>
        {BASIS_LABELS[request.basis]}, {dayLabel(request.vaDate)}
      </td>
      <td>
        <p className="finding-detail">{request.evidence}</p>
        <EvidenceFile request={request} onProblem={onProblem} />
      </td>
      <td>{STATE_LABELS[request.previousState]}</td>
      <td>{WHEN.format(new Date(request.requestedAt))}</td>
      <td>
        {request.sodConflict ? (
          <span className="flag flag--warning">The other VA POC decides</span>
        ) : (
          <>
            <button type="button" className="button" disabled={busy} onClick={() => onDecide(request, "approve")}>
              Approve<span className="visually-hidden"> the Exemption for {request.systemCode}</span>
            </button>{" "}
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              onClick={() => onDecide(request, "reject")}
            >
              Reject<span className="visually-hidden"> the Exemption for {request.systemCode}</span>
            </button>
          </>
        )}
      </td>
    </tr>
  );
}

/**
 * The Exemption requests waiting on a VA POC (stories 45 and 46), oldest first, with what the SMO/Tech Lead sent: an
 * approval Exempts the VA Submission (conditional for a scheduled VA), a rejection puts it back where it was. Deciding
 * needs a sign-in from the last five minutes: without one the app explains the fresh sign-in and brings the VA POC back
 * here to press it again. Every refusal is the server's; the list is read again after each decision and each 409.
 */
export function ExemptionQueueScreen() {
  const cycle = useSelectedCycle();
  const load = useCallback(() => fetchExemptionQueue(cycle), [cycle]);
  const queue = useResource(load);
  const [busyId, setBusyId] = useState<string | undefined>(undefined);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const { reload } = queue;

  async function decide(request: QueuedExemption, decision: "approve" | "reject") {
    setBusyId(request.exemptionId);
    setProblem(undefined);
    try {
      await decideExemption(request.exemptionId, decision);
      reload();
    } catch (failure) {
      const refused = failure instanceof ApiError ? failure.problem : UNREACHABLE;
      setProblem(refused);
      if (refused.status === 409) reload();
    } finally {
      setBusyId(undefined);
    }
  }

  const requests = queue.data?.requests ?? [];
  return (
    <div className="card">
      <h2>Exemption requests</h2>
      <p className="muted">
        Requests waiting on a decision, oldest first. While one waits its VA Submission is frozen. The evidence is what
        the SMO/Tech Lead wrote.
      </p>
      {problem ? <ErrorPanel problem={problem} /> : null}
      {queue.error ? <ErrorPanel problem={queue.error.problem} /> : null}
      {queue.data && requests.length === 0 ? <p className="muted">No Exemption request is waiting.</p> : null}
      {requests.length > 0 ? (
        <div className="table-scroll">
          <table className="data-table">
            <caption className="visually-hidden">Exemption requests waiting on a decision</caption>
            <thead>
              <tr>
                <th scope="col">System</th>
                <th scope="col">Quarter</th>
                <th scope="col">Rests on</th>
                <th scope="col">Evidence</th>
                <th scope="col">Before the request</th>
                <th scope="col">Requested</th>
                <th scope="col">
                  <span className="visually-hidden">Decision</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {requests.map((request) => (
                <QueueRow
                  key={request.exemptionId}
                  request={request}
                  busy={busyId !== undefined}
                  onDecide={(chosen, decision) => void decide(chosen, decision)}
                  onProblem={setProblem}
                />
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
}
