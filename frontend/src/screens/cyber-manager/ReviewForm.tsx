import { useState } from "react";
import { MAX_COMMENT_LENGTH, type ReviewDecision, type ReviewInput } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/**
 * Confirm or Dispute one item (story 50), or a whole Bulk-set batch (story 51): a comment box, required to Dispute and optional to confirm, labelled with what
 * it is about and its classification (IM8 dp-8) and limited to the server's length, and the two buttons. The server
 * decides what is kept and answers a problem, shown here and linked to the comment box, when it refuses. A 409 means the
 * item changed or moved on since it was read (a new proposal or justification, or the VA Submission left In Review), so
 * the screen reads it again, as it does after a review.
 */
export function ReviewForm({
  idPrefix,
  subject,
  classification,
  send,
  onReviewed,
  actionSuffix = "",
}: {
  idPrefix: string;
  /** What is reviewed, in words, for the comment's label and the buttons' accessible names. */
  subject: string;
  classification: string;
  send: (input: ReviewInput) => Promise<unknown>;
  onReviewed: () => void;
  /** Words after Confirm and Dispute on the buttons, such as " batch"; none for one item. */
  actionSuffix?: string;
}) {
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const commentId = `${idPrefix}-comment`;
  const problemId = `${idPrefix}-problem`;

  async function review(decision: ReviewDecision) {
    setBusy(true);
    setProblem(undefined);
    try {
      await send(comment.trim() === "" ? { decision } : { decision, comment });
      setComment("");
      onReviewed();
    } catch (failure) {
      const refused = failure instanceof ApiError ? failure.problem : UNREACHABLE;
      setProblem(refused);
      if (refused.status === 409) onReviewed();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="review-form">
      <ClassifiedField
        label={`Comment on ${subject} (required to Dispute)`}
        classification={classification}
        id={commentId}
      >
        <textarea
          id={commentId}
          rows={2}
          maxLength={MAX_COMMENT_LENGTH}
          value={comment}
          aria-invalid={problem?.field === "comment"}
          aria-describedby={problem ? problemId : undefined}
          onChange={(event) => setComment(event.target.value)}
        />
      </ClassifiedField>
      {problem ? (
        <div id={problemId}>
          <ErrorPanel problem={problem} />
        </div>
      ) : null}
      <p className="review-form__actions">
        <button type="button" className="button" disabled={busy} onClick={() => void review("CONFIRMED")}>
          Confirm{actionSuffix}
          <span className="visually-hidden"> {subject}</span>
        </button>{" "}
        <button
          type="button"
          className="button button--secondary"
          disabled={busy || comment.trim() === ""}
          onClick={() => void review("DISPUTED")}
        >
          Dispute{actionSuffix}
          <span className="visually-hidden"> {subject}</span>
        </button>
      </p>
    </div>
  );
}
