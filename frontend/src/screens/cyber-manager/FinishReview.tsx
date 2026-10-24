import { useCallback, useState } from "react";
import { fetchReadiness, MAX_COMMENT_LENGTH, type ReviewOutcome } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { passToEndorsement, returnForRework, type Submission } from "../../api/submissions.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { ReasonList } from "../shared/ReasonList.tsx";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/**
 * The "Finish the review" panel of a VA Submission In Review. It is rendered only In Review, so the readiness read (an
 * audited read for a Cyber Manager) happens only then, and again after each review the screen counts in `reviews`.
 */
export function FinishReview({
  submissionId,
  reviews,
  classification,
  onMoved,
}: {
  submissionId: string;
  /** How many reviews the screen has made: each one reads where the review stands again. */
  reviews: number;
  classification: string;
  onMoved: () => void;
}) {
  // `reviews` is in the dependencies on purpose: a new count is a new read.
  const load = useCallback(() => fetchReadiness(submissionId), [submissionId, reviews]);
  const readiness = useResource(load);
  if (readiness.error) return <ErrorPanel problem={readiness.error.problem} />;
  if (!readiness.data) return <p className="muted">Loading where the review stands…</p>;
  return (
    <FinishReviewPanel
      submissionId={submissionId}
      outcome={readiness.data.review}
      classification={classification}
      onMoved={onMoved}
    />
  );
}

/**
 * How the Cyber Manager finishes a review (stories 55 and 57): pass the VA Submission to the VA POCs once everything is
 * confirmed, or return it for rework once something is Disputed. Each control says when it is possible, and what is left
 * is listed in the server's words (the same reasons the pass refuses with). The server decides every refusal; its problem
 * is shown here, and after a 409 the screen reads the VA Submission again. The return comment is optional, carries the
 * classification label (IM8 dp-8) and the server's length limit, and is linked to the server's problem.
 */
function FinishReviewPanel({
  submissionId,
  outcome,
  classification,
  onMoved,
}: {
  submissionId: string;
  outcome: ReviewOutcome;
  classification: string;
  onMoved: () => void;
}) {
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const left = outcome.endorsementBlockers;

  async function move(send: () => Promise<Submission>) {
    setBusy(true);
    setProblem(undefined);
    try {
      await send();
      setComment("");
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
    <section className="card" aria-labelledby="review-outcome">
      <h3 id="review-outcome">Finish the review</h3>
      {problem ? (
        <div id="review-outcome-problem">
          <ErrorPanel problem={problem} />
          {problem.reasons && problem.reasons.length > 0 ? <ReasonList reasons={problem.reasons} /> : null}
        </div>
      ) : null}

      <h4>Pass to endorsement</h4>
      <p className="muted">
        Sends the VA Submission to the two VA POCs for endorsement. Possible once every Finding and every not-applicable
        justification is confirmed.
      </p>
      {left.length > 0 ? (
        <div className="notice" role="status">
          <p>Still to do before passing it on:</p>
          <ReasonList reasons={left} />
        </div>
      ) : null}
      <p>
        <button
          type="button"
          className="button"
          disabled={busy || left.length > 0}
          onClick={() => void move(() => passToEndorsement(submissionId))}
        >
          Pass to endorsement
        </button>
      </p>

      <h4>Return for rework</h4>
      <p className="muted">
        Sends the VA Submission back to the SMO/Tech Lead with every Disputed Finding and justification attached; what
        you confirmed stays confirmed. Possible once at least one Finding or justification is Disputed.
      </p>
      {outcome.returnable ? null : <p className="muted">Nothing is Disputed yet.</p>}
      <ClassifiedField
        label="Comment for the SMO/Tech Lead (optional)"
        classification={classification}
        id="return-comment"
      >
        <textarea
          id="return-comment"
          rows={3}
          maxLength={MAX_COMMENT_LENGTH}
          value={comment}
          aria-invalid={problem?.field === "comment"}
          aria-describedby={problem ? "review-outcome-problem" : undefined}
          onChange={(event) => setComment(event.target.value)}
        />
      </ClassifiedField>
      <p>
        <button
          type="button"
          className="button button--secondary"
          disabled={busy || !outcome.returnable}
          onClick={() => void move(() => returnForRework(submissionId, comment))}
        >
          Return for rework
        </button>
      </p>
    </section>
  );
}
