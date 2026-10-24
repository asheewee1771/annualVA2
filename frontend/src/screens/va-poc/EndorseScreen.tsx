import { useCallback, useEffect, useState } from "react";
import { fetchFindings, MAX_COMMENT_LENGTH } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { fetchScanReportModes } from "../../api/scanReports.ts";
import { takeRestoredDraft } from "../../api/stepUp.ts";
import { endorseSubmission, fetchSubmission, returnForRework, type Submission } from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { FindingReview } from "../shared/FindingCells.tsx";
import { FINDINGS_PAGE_SIZE, FindingsList } from "../shared/FindingsList.tsx";
import { NotApplicableSection } from "../shared/NotApplicableSection.tsx";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { endorsedSubmissionOf, VA_POC_PATH } from "./paths.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/** The step-up intent of a VA POC's return: the server's fixed code, under which a typed comment is kept. */
const RETURN_INTENT = "submission-return";

/** Where the server's problem is shown, so the comment box can point at it. */
const ENDORSE_PROBLEM_ID = "endorse-problem";

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

/** A VA POC's move (an endorsement or a return) as both panels run it: one at a time on the screen. */
interface Mover {
  busy: boolean;
  run: (send: () => Promise<Submission>) => Promise<void>;
}

/**
 * Runs the VA POC's moves one at a time. Both need a sign-in from the last five minutes: without one the app explains
 * the fresh sign-in and brings the VA POC back here to press it again. The server decides every refusal (segregation of
 * duties included); the screen shows its problem, which outlives the panels, and after a move or a 409 reads the VA
 * Submission again. After a move the buttons stay disabled until the screen has read the new state, which removes the
 * panels; after a refusal they are enabled again.
 */
function useMove(onMoved: () => void, onProblem: (problem: Problem | undefined) => void): Mover {
  const [busy, setBusy] = useState(false);

  async function run(send: () => Promise<Submission>) {
    setBusy(true);
    onProblem(undefined);
    try {
      await send();
      onMoved();
    } catch (failure) {
      const refused = failure instanceof ApiError ? failure.problem : UNREACHABLE;
      onProblem(refused);
      setBusy(false);
      if (refused.status === 409) onMoved();
    }
  }

  return { busy, run };
}

/** Endorse, for a VA Submission Awaiting Endorsement (stories 65, 67 and 70). */
function EndorsePanel({ submissionId, mover }: { submissionId: string; mover: Mover }) {
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
        <button
          type="button"
          className="button"
          disabled={mover.busy}
          onClick={() => void mover.run(() => endorseSubmission(submissionId))}
        >
          Endorse
        </button>
      </p>
    </section>
  );
}

/**
 * The comment a VA POC was returning with when the server asked for a fresh sign-in, with the VA Submission it was for:
 * the page the sign-in brought them back to. Read once as the page loads, and forgotten once a Return panel has read it,
 * so it never fills the Return panel of another VA Submission or comes back later.
 */
let leftForSignIn = commentLeftForSignIn();

function commentLeftForSignIn(): { submissionId: string; comment: string } | undefined {
  const draft = takeRestoredDraft(RETURN_INTENT);
  const submissionId = endorsedSubmissionOf(window.location.pathname);
  return submissionId !== undefined &&
    typeof draft === "object" &&
    draft !== null &&
    "comment" in draft &&
    typeof draft.comment === "string"
    ? { submissionId, comment: draft.comment }
    : undefined;
}

/**
 * Return for rework, for a VA Submission Awaiting Endorsement (stories 66 and 63): back to the SMO/Tech Lead with a
 * comment, to challenge something the Cyber Manager confirmed. The comment is required, carries the classification label
 * (IM8 dp-8) and the server's length limit, and is linked to the server's problem until it is edited. A comment typed
 * before the fresh sign-in is there again on the way back to this VA Submission.
 */
function ReturnPanel({
  submissionId,
  classification,
  problem,
  mover,
}: {
  submissionId: string;
  classification: string;
  problem: Problem | undefined;
  mover: Mover;
}) {
  const [comment, setComment] = useState(() =>
    leftForSignIn?.submissionId === submissionId ? leftForSignIn.comment : "",
  );
  const [edited, setEdited] = useState(false);
  useEffect(() => {
    leftForSignIn = undefined;
  }, []);
  const flagged = problem?.field === "comment" && !edited;

  async function returnIt() {
    setEdited(false);
    await mover.run(() => returnForRework(submissionId, comment));
  }

  return (
    <section className="card" aria-labelledby="va-poc-return-title">
      <h3 id="va-poc-return-title">Return for rework</h3>
      <p className="muted">
        Sends the VA Submission back to the SMO/Tech Lead with your comment, to challenge something the Cyber Manager
        confirmed. What was confirmed stays confirmed; once the SMO/Tech Lead submits it again, its Cyber Manager
        re-reviews what changed before it comes back to the VA POCs. The same fresh sign-in and segregation of duties
        apply as for endorsing.
      </p>
      <ClassifiedField
        label="Comment for the SMO/Tech Lead (required)"
        classification={classification}
        id="va-poc-return-comment"
      >
        <textarea
          id="va-poc-return-comment"
          rows={3}
          required
          maxLength={MAX_COMMENT_LENGTH}
          value={comment}
          aria-invalid={flagged}
          aria-describedby={flagged ? ENDORSE_PROBLEM_ID : undefined}
          onChange={(event) => {
            setComment(event.target.value);
            setEdited(true);
          }}
        />
      </ClassifiedField>
      <p>
        <button
          type="button"
          className="button button--secondary"
          disabled={mover.busy || comment.trim() === ""}
          onClick={() => void returnIt()}
        >
          Return for rework
        </button>
      </p>
    </section>
  );
}

/**
 * A VA POC's view of one VA Submission (stories 65, 66, 67 and 70): its state with the Overdue and Endorsed Late flags,
 * the not-applicable justifications and every Finding with what the Cyber Manager confirmed, all read only, and Endorse
 * and Return for rework while it is Awaiting Endorsement. Everything a person typed is shown as text.
 */
export function EndorseScreen({ submissionId, classification }: { submissionId: string; classification: string }) {
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
  const mover = useMove(moved, setProblem);

  return (
    <div className="card">
      <p>
        <AppLink path={VA_POC_PATH}>Back to the Quarter board</AppLink>
      </p>
      <h2>Endorse VA Submission</h2>
      {submission.error ? <ErrorPanel problem={submission.error.problem} /> : null}
      {submission.data ? <SubmissionSummary submission={submission.data} /> : null}
      {problem ? (
        <div id={ENDORSE_PROBLEM_ID}>
          <ErrorPanel problem={problem} />
        </div>
      ) : null}
      {submission.data?.state === "AWAITING_ENDORSEMENT" ? (
        <>
          <EndorsePanel submissionId={submissionId} mover={mover} />
          <ReturnPanel submissionId={submissionId} classification={classification} problem={problem} mover={mover} />
        </>
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
