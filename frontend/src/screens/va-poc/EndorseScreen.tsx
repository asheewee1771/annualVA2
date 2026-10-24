import { useCallback, useState } from "react";
import { fetchFindings } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { fetchScanReportModes } from "../../api/scanReports.ts";
import { endorseSubmission, fetchSubmission, type Submission } from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { FindingReview } from "../shared/FindingCells.tsx";
import { FINDINGS_PAGE_SIZE, FindingsList } from "../shared/FindingsList.tsx";
import { NotApplicableSection } from "../shared/NotApplicableSection.tsx";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { VA_POC_PATH } from "./paths.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

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
 * decides every refusal (segregation of duties included); the screen shows its problem, which outlives this panel, and
 * after a 409 reads the VA Submission again. After an endorsement the button stays disabled until the screen has read
 * the new state, which removes the panel.
 */
function EndorsePanel({
  submissionId,
  onMoved,
  onProblem,
}: {
  submissionId: string;
  onMoved: () => void;
  onProblem: (problem: Problem | undefined) => void;
}) {
  const [busy, setBusy] = useState(false);

  async function endorse() {
    setBusy(true);
    onProblem(undefined);
    try {
      await endorseSubmission(submissionId);
      onMoved();
    } catch (failure) {
      const refused = failure instanceof ApiError ? failure.problem : UNREACHABLE;
      onProblem(refused);
      setBusy(false);
      if (refused.status === 409) onMoved();
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
      <p>
        <button type="button" className="button" disabled={busy} onClick={() => void endorse()}>
          Endorse
        </button>
      </p>
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
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const loadSubmission = useCallback(() => fetchSubmission(submissionId), [submissionId]);
  const loadReports = useCallback(() => fetchScanReportModes(submissionId), [submissionId]);
  const loadFindings = useCallback(
    (page: number) => fetchFindings(submissionId, {}, page, FINDINGS_PAGE_SIZE),
    [submissionId],
  );
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
      {problem ? <ErrorPanel problem={problem} /> : null}
      {submission.data?.state === "AWAITING_ENDORSEMENT" ? (
        <EndorsePanel submissionId={submissionId} onMoved={moved} onProblem={setProblem} />
      ) : null}
      {reports.error ? <ErrorPanel problem={reports.error.problem} /> : null}
      {reports.data ? <NotApplicableSection id="endorse-not-applicable" reports={reports.data} /> : null}
      <section className="card" aria-labelledby="endorse-findings">
        <h3 id="endorse-findings">Findings</h3>
        <FindingsList
          load={loadFindings}
          refreshKey={refreshKey}
          heading="Every Finding"
          caption="Findings of the VA Submission, the most severe first"
          reviewCell={(finding) => <FindingReview finding={finding} />}
        />
      </section>
    </div>
  );
}
