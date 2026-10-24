import { useCallback, useState } from "react";
import {
  fetchReadiness,
  fetchReworkQueue,
  type ReworkItem,
  type ReworkQueue,
  type ReworkStage,
} from "../../api/findings.ts";
import { fetchReportFiles, type ReportFile } from "../../api/reportFiles.ts";
import { fetchScanReportModes, type ScanReportMode } from "../../api/scanReports.ts";
import { fetchReturns, fetchSubmission, type SubmissionReturn } from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { reportTitle } from "../shared/scanReports.ts";
import { DeclarationForm } from "./DeclarationForm.tsx";
import { submissionPath, SMO_AREA_PATH } from "./paths.ts";
import { ReworkFinding } from "./ReworkFinding.tsx";
import { SubmissionSummary, WHEN } from "./SubmissionSummary.tsx";

const STAGE_LABELS: Readonly<Record<ReworkStage, string>> = {
  DISPUTED: "Disputed",
  NEW_CRITICAL_HIGH: "New Critical and High",
  NEW_MEDIUM_LOW: "New Medium, Low and Informational",
};

/** A not-applicable justification the return Disputed: why, the wording Disputed, and where it stands now. */
function DisputedJustification({ submissionId, report }: { submissionId: string; report: ScanReportMode }) {
  const review = report.latestReview;
  return (
    <li>
      <strong>{reportTitle(report.reportType)}</strong> is marked not applicable.
      {review?.comment ? <p className="finding-detail">Why it was Disputed: {review.comment}</p> : null}
      {review?.justification ? <p className="muted finding-detail">Wording Disputed: {review.justification}</p> : null}
      {report.reviewState === "DISPUTED" ? (
        <p>
          <AppLink path={submissionPath(submissionId)}>Rewrite the justification on the prepare screen</AppLink>
        </p>
      ) : (
        <p className="muted">Rewritten: it awaits the Cyber Manager's review.</p>
      )}
    </li>
  );
}

/**
 * The latest return: who returned it and when, their comment, and the not-applicable justifications it Disputed. A VA
 * POC's return (story 63) Disputes nothing and confirmed Findings stay confirmed, so the queue may be empty: the comment
 * says what to change, on the prepare screen, before resubmitting to the Cyber Manager.
 */
function ReturnSummary({
  submissionId,
  latest,
  reports,
}: {
  submissionId: string;
  latest: SubmissionReturn;
  reports: readonly ScanReportMode[];
}) {
  const disputed = reports.filter((report) =>
    latest.disputedReports.some((item) => item.reportType === report.reportType),
  );
  const byVaPoc = latest.step === "ENDORSE";
  return (
    <section className="card" aria-labelledby="return-summary">
      <h3 id="return-summary">Why it came back</h3>
      <p className="muted">
        Returned for rework by {byVaPoc ? "a VA POC" : "the Cyber Manager"} on{" "}
        {WHEN.format(new Date(latest.returnedAt))}.
      </p>
      {latest.comment !== null ? (
        <p className="finding-detail">{latest.comment}</p>
      ) : (
        <p className="muted">No comment on the return: each Dispute says why.</p>
      )}
      {byVaPoc ? (
        <p className="muted">
          The VA POC challenged something the Cyber Manager confirmed; nothing is Disputed, so the queue below may be
          empty. Change what the comment asks for on{" "}
          <AppLink path={submissionPath(submissionId)}>the prepare screen</AppLink>, then resubmit: the Cyber Manager
          re-reviews what you changed before it goes back to the VA POCs.
        </p>
      ) : null}
      {disputed.length > 0 ? (
        <>
          <p>Not-applicable justifications Disputed:</p>
          <ul>
            {disputed.map((report) => (
              <DisputedJustification key={report.reportType} submissionId={submissionId} report={report} />
            ))}
          </ul>
        </>
      ) : null}
    </section>
  );
}

/** Where the SMO/Tech Lead is in the queue, with Previous and Skip, or that nothing is left. */
function QueuePosition({ queue, at, onMove }: { queue: ReworkQueue; at: number; onMove: (index: number) => void }) {
  if (queue.items.length === 0) {
    return <p>Nothing is left to rework. Resubmit it for review when you are ready.</p>;
  }
  return (
    <p>
      Finding {at + 1} of {queue.total}{" "}
      <button type="button" className="button button--secondary" disabled={at === 0} onClick={() => onMove(at - 1)}>
        Previous
      </button>{" "}
      <button
        type="button"
        className="button button--secondary"
        disabled={at >= queue.items.length - 1}
        onClick={() => onMove(at + 1)}
      >
        Skip
      </button>
    </p>
  );
}

/** Each stage in queue order with what is left in it and the current Finding's stage marked, the keys, and the position. */
function QueueSection({
  queue,
  current,
  at,
  onMove,
}: {
  queue: ReworkQueue;
  current: ReworkItem | undefined;
  at: number;
  onMove: (index: number) => void;
}) {
  return (
    <section className="card" aria-labelledby="rework-queue">
      <h3 id="rework-queue">Rework queue</h3>
      <ol className="rework-stages" aria-label="Stages of the rework queue">
        {queue.stages.map(({ stage, count }) => (
          <li key={stage} aria-current={stage === current?.stage ? "step" : undefined}>
            {STAGE_LABELS[stage]}: {count} left
          </li>
        ))}
      </ol>
      <p className="muted">
        Keys: <kbd>1</kbd> to <kbd>4</kbd> pick a status, <kbd>Enter</kbd> saves it and moves to the next Finding,{" "}
        <kbd>Esc</kbd> leaves a text box. While you type in a box, the keys are text.
      </p>
      <QueuePosition queue={queue} at={at} onMove={onMove} />
      {queue.total > queue.items.length ? (
        <p className="muted">
          The first {queue.items.length} are shown; the rest come into the queue as these are done.
        </p>
      ) : null}
    </section>
  );
}

/** Everything the rework screen reads, each read on its own. */
function useReworkData(submissionId: string) {
  return {
    submission: useResource(useCallback(() => fetchSubmission(submissionId), [submissionId])),
    queue: useResource(useCallback(() => fetchReworkQueue(submissionId), [submissionId])),
    returns: useResource(useCallback(() => fetchReturns(submissionId), [submissionId])),
    reports: useResource(useCallback(() => fetchScanReportModes(submissionId), [submissionId])),
    files: useResource(useCallback(() => fetchReportFiles(submissionId), [submissionId])),
    readiness: useResource(useCallback(() => fetchReadiness(submissionId), [submissionId])),
  };
}

/** The supporting evidence files that have finished their checks: what a Risk Accepted proposal may name as its memo. */
function readyEvidence(files: readonly ReportFile[] | undefined): ReportFile[] {
  return (files ?? []).filter((file) => file.kind === "EVIDENCE" && file.status === "READY");
}

/** The queue and the Finding the SMO/Tech Lead is on; a saved Finding leaves the queue and the next takes its place. */
function QueueAndFinding({
  queue,
  memos,
  classification,
  editable,
  onSaved,
}: {
  queue: ReworkQueue;
  memos: readonly ReportFile[];
  classification: string;
  editable: boolean;
  onSaved: () => void;
}) {
  const [index, setIndex] = useState(0);
  const at = Math.min(index, Math.max(queue.items.length - 1, 0));
  const current = queue.items[at];
  return (
    <>
      <QueueSection queue={queue} current={current} at={at} onMove={setIndex} />
      {current ? (
        <ReworkFinding
          key={current.finding.id}
          item={current}
          memos={memos}
          classification={classification}
          editable={editable}
          onSaved={onSaved}
        />
      ) : null}
    </>
  );
}

/**
 * The rework queue of a VA Submission Returned for Rework (stories 60, 61 and 62): why it came back (the return's
 * comment and the Disputed justifications), the stages with what is left in each, one Finding at a time with the Cyber
 * Manager's Dispute as text and the four statuses on keys 1 to 4, Enter to save and move on, and Resubmit. Previous and
 * Skip move without saving. The queue, the state and what stands in the way of resubmitting are the server's.
 */
export function ReworkScreen({ submissionId, classification }: { submissionId: string; classification: string }) {
  const { submission, queue, returns, reports, files, readiness } = useReworkData(submissionId);
  const data = submission.data;
  const editable = data?.state === "RETURNED_FOR_REWORK";
  const latest = returns.data?.[0];
  const error = [queue, returns, reports, files].map((resource) => resource.error).find(Boolean);

  return (
    <>
      <p>
        <AppLink path={SMO_AREA_PATH}>Back to your Systems</AppLink> ·{" "}
        <AppLink path={submissionPath(submissionId)}>The whole VA Submission</AppLink>
      </p>
      <SubmissionSummary heading="Rework the VA Submission" submission={submission} />
      {data && !editable ? (
        <div className="notice" role="status">
          <p>It is not Returned for Rework, so nothing is reworked here now.</p>
        </div>
      ) : null}
      {error ? <ErrorPanel problem={error.problem} /> : null}
      {latest ? <ReturnSummary submissionId={submissionId} latest={latest} reports={reports.data ?? []} /> : null}
      {queue.data ? (
        <QueueAndFinding
          queue={queue.data}
          memos={readyEvidence(files.data)}
          classification={classification}
          editable={editable}
          onSaved={() => {
            queue.reload();
            readiness.reload();
          }}
        />
      ) : null}
      {data && editable ? (
        <DeclarationForm
          submission={data}
          readiness={readiness}
          classification={classification}
          onSubmitted={submission.reload}
        />
      ) : null}
    </>
  );
}
