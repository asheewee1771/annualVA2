import { useCallback, useState } from "react";
import { fetchFindings, type FindingPage } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { fetchScanReportModes, type ScanReportMode } from "../../api/scanReports.ts";
import { endorseSubmission, fetchSubmission, type Submission } from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { FindingCells, FindingReview, JustificationSummary } from "../shared/FindingCells.tsx";
import { Pager } from "../shared/Pager.tsx";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { VA_POC_PATH } from "./paths.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/** How many Findings one page shows. */
const PAGE_SIZE = 100;

/** Which Quarter the VA Submission is in, its state, and its Overdue and Endorsed Late flags. */
function SubmissionSummary({ submission }: { submission: Submission }) {
  return (
    <p className="muted">
      VA Cycle {submission.cycle}, Q{submission.quarter}: {STATE_LABELS[submission.state]}
      {submission.overdue ? (
        <>
          {" "}
          <span className="flag flag--danger">Overdue</span>
        </>
      ) : null}
      {submission.endorsedLate ? (
        <>
          {" "}
          <span className="flag flag--warning">Endorsed Late</span>
        </>
      ) : null}
      . Endorsement is due by {submission.endorseDue}, the Quarter&apos;s last day.
    </p>
  );
}

/**
 * Endorse, for a VA Submission Awaiting Endorsement (stories 65, 67 and 70). It needs a sign-in from the last five
 * minutes: without one the app explains the fresh sign-in and brings the VA POC back here to press it again. The server
 * decides every refusal (segregation of duties included) and its problem is shown here; after a 409 the screen reads the
 * VA Submission again.
 */
function EndorsePanel({ submissionId, onMoved }: { submissionId: string; onMoved: () => void }) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function endorse() {
    setBusy(true);
    setProblem(undefined);
    try {
      await endorseSubmission(submissionId);
      onMoved();
    } catch (failure) {
      const refused = failure instanceof ApiError ? failure.problem : UNREACHABLE;
      setProblem(refused);
      if (refused.status === 409) onMoved();
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card" aria-labelledby="endorse-title">
      <h3 id="endorse-title">Endorse</h3>
      <p className="muted">
        Accepts this VA Submission as the System&apos;s VA for this Cycle. Every Finding and justification below was
        confirmed by its Cyber Manager. After the Quarter&apos;s last day it is marked Endorsed Late. You need to have
        signed in within the last five minutes; if you acted at another step of it, or are its System&apos;s SMO/Tech
        Lead or Cyber Manager, the other VA POC endorses it.
      </p>
      {problem ? <ErrorPanel problem={problem} /> : null}
      <p>
        <button type="button" className="button" disabled={busy} onClick={() => void endorse()}>
          Endorse
        </button>
      </p>
    </section>
  );
}

/** The Scan Reports marked not applicable, each justification with its review; read only. */
function NotApplicableSection({ reports }: { reports: readonly ScanReportMode[] }) {
  const notApplicable = reports.filter((report) => report.mode === "NOT_APPLICABLE");
  return (
    <section className="card" aria-labelledby="endorse-not-applicable">
      <h3 id="endorse-not-applicable">Scan Reports marked not applicable ({notApplicable.length})</h3>
      {notApplicable.length === 0 ? <p className="muted">Every Scan Report was provided.</p> : null}
      {notApplicable.map((report) => (
        <div key={report.reportType} className="review-item">
          <JustificationSummary report={report} />
        </div>
      ))}
    </section>
  );
}

/** One page of the Findings, the most severe first, with what was proposed and its review; read only. */
function FindingsTable({ result, onPage }: { result: FindingPage; onPage: (page: number) => void }) {
  return (
    <>
      {result.items.length === 0 ? (
        <p className="muted">There are none here.</p>
      ) : (
        <div className="table-scroll">
          <table className="data-table">
            <caption className="visually-hidden">Findings of the VA Submission, the most severe first</caption>
            <thead>
              <tr>
                <th scope="col">Severity</th>
                <th scope="col">Finding</th>
                <th scope="col">Proposed status</th>
                <th scope="col">Review</th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((finding) => (
                <tr key={finding.id}>
                  <FindingCells finding={finding} />
                  <td>
                    <FindingReview finding={finding} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {result.total > PAGE_SIZE ? (
        <Pager page={result.page} pages={Math.ceil(result.total / PAGE_SIZE)} total={result.total} onPage={onPage} />
      ) : null}
    </>
  );
}

function FindingsSection({ submissionId, refreshKey }: { submissionId: string; refreshKey: number }) {
  const [page, setPage] = useState(0);
  const load = useCallback(() => fetchFindings(submissionId, {}, page, PAGE_SIZE), [submissionId, page]);
  const findings = useResource(load, refreshKey);
  const result = findings.data;
  return (
    <section className="card" aria-labelledby="endorse-findings">
      <h3 id="endorse-findings">Findings ({result ? result.total : 0})</h3>
      {findings.error ? <ErrorPanel problem={findings.error.problem} /> : null}
      {findings.loading && !result ? <p className="muted">Loading the Findings…</p> : null}
      {result ? <FindingsTable result={result} onPage={setPage} /> : null}
    </section>
  );
}

/**
 * A VA POC's view of one VA Submission (stories 65, 67 and 70): its state with the Overdue and Endorsed Late flags, the
 * not-applicable justifications and every Finding with what the Cyber Manager confirmed, all read only, and Endorse
 * while it is Awaiting Endorsement. Everything a person typed is shown as text.
 */
export function EndorseScreen({ submissionId }: { submissionId: string }) {
  const [refreshKey, setRefreshKey] = useState(0);
  const loadSubmission = useCallback(() => fetchSubmission(submissionId), [submissionId]);
  const loadReports = useCallback(() => fetchScanReportModes(submissionId), [submissionId]);
  const submission = useResource(loadSubmission, refreshKey);
  const reports = useResource(loadReports, refreshKey);
  const moved = useCallback(() => setRefreshKey((n) => n + 1), []);

  return (
    <div className="card">
      <p>
        <AppLink path={VA_POC_PATH}>Back to the Quarter board</AppLink>
      </p>
      <h2>Endorse VA Submission</h2>
      {submission.error ? <ErrorPanel problem={submission.error.problem} /> : null}
      {submission.data ? <SubmissionSummary submission={submission.data} /> : null}
      {submission.data?.state === "AWAITING_ENDORSEMENT" ? (
        <EndorsePanel submissionId={submissionId} onMoved={moved} />
      ) : null}
      {reports.error ? <ErrorPanel problem={reports.error.problem} /> : null}
      {reports.data ? <NotApplicableSection reports={reports.data} /> : null}
      <FindingsSection submissionId={submissionId} refreshKey={refreshKey} />
    </div>
  );
}
