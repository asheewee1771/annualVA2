import { useState } from "react";
import type { Readiness } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import {
  fetchDeclarationText,
  submitSubmission,
  type DeclarationText,
  type Submission,
} from "../../api/submissions.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource, type Resource } from "../../hooks/useResource.ts";
import { ReasonList } from "../shared/ReasonList.tsx";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/** The words of the first submit from Draft, and of a resubmit once Returned for Rework. */
const SUBMIT = { verb: "submit", button: "Submit for review", busy: "Submitting…" } as const;
const RESUBMIT = { verb: "resubmit", button: "Resubmit for review", busy: "Resubmitting…" } as const;

function wordsFor(submission: Submission) {
  return submission.state === "RETURNED_FOR_REWORK" ? RESUBMIT : SUBMIT;
}

/** The current wording, marked when it is a placeholder, and the tick box that accepts it. */
function DeclarationWording({
  text,
  ticked,
  busy,
  classification,
  onTick,
}: {
  text: DeclarationText;
  ticked: boolean;
  busy: boolean;
  classification: string;
  onTick: (ticked: boolean) => void;
}) {
  return (
    <>
      <p className="muted">
        Version {text.version}
        {text.placeholder ? (
          <>
            {" "}
            <span className="flag flag--warning">Placeholder wording, not yet agreed</span>
          </>
        ) : null}
      </p>
      <p className="declaration-text">{text.text}</p>
      <ClassifiedField label="I accept the Declaration" classification={classification} id="declaration-accepted">
        <input
          id="declaration-accepted"
          type="checkbox"
          checked={ticked}
          disabled={busy}
          onChange={(event) => onTick(event.target.checked)}
        />
      </ClassifiedField>
    </>
  );
}

/** The server's refusal of a submit, with every reason it listed. */
function SubmitProblem({ problem }: { problem: Problem }) {
  return (
    <>
      <ErrorPanel problem={problem} />
      {problem.reasons && problem.reasons.length > 0 ? <ReasonList reasons={problem.reasons} /> : null}
    </>
  );
}

/**
 * Submits with the version shown. A refusal is kept to show; the wording is read again and must be ticked again, and what
 * stands in the way is read again.
 */
function useSubmit(
  submissionId: string,
  declaration: Resource<DeclarationText>,
  onSubmitted: () => void,
  onRefused: () => void,
) {
  const [ticked, setTicked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function submit(version: number) {
    setBusy(true);
    setProblem(undefined);
    try {
      await submitSubmission(submissionId, version);
      onSubmitted();
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
      setTicked(false);
      declaration.reload();
      onRefused();
    } finally {
      setBusy(false);
    }
  }

  return { ticked, setTicked, busy, problem, submit };
}

/**
 * The Declaration and Submit while the VA Submission is prepared or reworked (stories 40, 41 and 60): Submit for review
 * from Draft, Resubmit for review once Returned for Rework. It stays disabled, with every reason the server gives shown,
 * until nothing stands in the way and the current wording is ticked; the server checks again and lists its reasons if it
 * refuses.
 */
export function DeclarationForm({
  submission,
  readiness,
  classification,
  onSubmitted,
}: {
  submission: Submission;
  readiness: Resource<Readiness>;
  classification: string;
  onSubmitted: () => void;
}) {
  const declaration = useResource(fetchDeclarationText);
  const { ticked, setTicked, busy, problem, submit } = useSubmit(
    submission.id,
    declaration,
    onSubmitted,
    readiness.reload,
  );
  const text = declaration.data;
  const blockers = readiness.data?.blockers ?? [];
  const ready = readiness.data !== undefined && blockers.length === 0;
  const words = wordsFor(submission);

  return (
    <section className="card" aria-labelledby="declaration">
      <h3 id="declaration">Declaration</h3>
      {declaration.error ? <ErrorPanel problem={declaration.error.problem} /> : null}
      {text ? (
        <DeclarationWording
          text={text}
          ticked={ticked}
          busy={busy}
          classification={classification}
          onTick={setTicked}
        />
      ) : (
        <p className="muted">Loading the Declaration…</p>
      )}
      {blockers.length > 0 ? (
        <div className="notice" role="status">
          <p>Before you can {words.verb}:</p>
          <ReasonList reasons={blockers} />
        </div>
      ) : null}
      {problem ? <SubmitProblem problem={problem} /> : null}
      <p>
        <button
          type="button"
          className="button"
          disabled={!ready || !ticked || !text || busy}
          onClick={() => text && void submit(text.version)}
        >
          {busy ? words.busy : words.button}
        </button>
      </p>
    </section>
  );
}
