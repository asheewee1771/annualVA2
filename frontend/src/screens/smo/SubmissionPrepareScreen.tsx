import { useCallback, useEffect, useState } from "react";
import { dayLabel } from "../../api/cycles.ts";
import { fetchReadiness, type Readiness } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { fetchReportFiles } from "../../api/reportFiles.ts";
import { fetchScanReportModes } from "../../api/scanReports.ts";
import {
  fetchDeclarationText,
  fetchSubmission,
  isEditable,
  submitSubmission,
  type AcceptedDeclaration,
  type DeclarationText,
  type Submission,
} from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource, type Resource } from "../../hooks/useResource.ts";
import { STATE_LABELS } from "../shared/systemStates.ts";
import { EditableContext } from "./Editable.tsx";
import { FindingsSection } from "./FindingsSection.tsx";
import { SMO_AREA_PATH } from "./paths.ts";
import { ScanReportsSection } from "./ScanReportsSection.tsx";
import { ReasonList } from "../shared/ReasonList.tsx";

const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/** The Declaration as it was accepted at the latest submit: the exact wording kept with the VA Submission. */
function AcceptedDeclarationView({ declaration }: { declaration: AcceptedDeclaration }) {
  return (
    <>
      <p>
        Declaration version {declaration.version}, accepted {WHEN.format(new Date(declaration.acceptedAt))}:
      </p>
      <p className="declaration-text">{declaration.text}</p>
    </>
  );
}

/** Once submitted: when, that it is read-only now, and the Declaration as it was accepted. */
function SubmittedDeclaration({ submission }: { submission: Submission }) {
  return (
    <section className="card" aria-labelledby="declaration">
      <h3 id="declaration">Declaration</h3>
      {submission.submittedAt ? (
        <p>
          Submitted for review on {WHEN.format(new Date(submission.submittedAt))}. This VA Submission is read-only now.
        </p>
      ) : null}
      {submission.declaration ? <AcceptedDeclarationView declaration={submission.declaration} /> : null}
    </section>
  );
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
 * The Declaration and Submit while the VA Submission is prepared (stories 40 and 41). Submit stays disabled, with every
 * reason the server gives shown, until nothing stands in the way and the current wording is ticked; the server checks
 * again and lists its reasons if it refuses.
 */
function DeclarationForm({
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
          <p>Before you can submit:</p>
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
          {busy ? "Submitting…" : "Submit for review"}
        </button>
      </p>
    </section>
  );
}

/** The VA Submission's state and its dates, as the server gives them. */
function SubmissionSummary({ submission }: { submission: Resource<Submission> }) {
  const { data, error } = submission;
  return (
    <div className="card">
      <h2>Prepare the VA Submission</h2>
      {error ? <ErrorPanel problem={error.problem} /> : null}
      {submission.loading && !data ? <p className="muted">Loading the VA Submission…</p> : null}
      {data ? (
        <>
          <p>
            VA Cycle {data.cycle} · Q{data.quarter} · {STATE_LABELS[data.state]}
            {data.overdue ? (
              <>
                {" "}
                <span className="flag flag--danger">Overdue</span>
              </>
            ) : null}
          </p>
          {data.overdueSince ? (
            <p className="muted">Overdue since {WHEN.format(new Date(data.overdueSince))}.</p>
          ) : null}
          <p className="muted">
            Submit by {dayLabel(data.submitDue)}, reviewed by {dayLabel(data.reviewDue)}, endorsed by{" "}
            {dayLabel(data.endorseDue)}. The windows are soft: nothing locks when one passes.
          </p>
        </>
      ) : null}
    </div>
  );
}

/**
 * Where the SMO/Tech Lead prepares a VA Submission (stories 19, 20, 23, 24, 25, 26, 28, 35, 40 and 41): the three Scan
 * Report cards with how each is provided, their files and each file's pipeline status (asked for again while a file is
 * still being checked), the scan date and target of each report file and the form to enter a Finding by hand, the Findings
 * read from them or entered with a status to propose for each, and the Declaration with Submit. Once submitted, the screen
 * is read-only. The state, the dates and what stands in the way of submitting are the server's.
 */
export function SubmissionPrepareScreen({
  submissionId,
  classification,
}: {
  submissionId: string;
  classification: string;
}) {
  const load = useCallback(() => fetchSubmission(submissionId), [submissionId]);
  const submission = useResource(load);
  const { data } = submission;
  const loadFiles = useCallback(() => fetchReportFiles(submissionId), [submissionId]);
  const files = useResource(loadFiles);
  const loadModes = useCallback(() => fetchScanReportModes(submissionId), [submissionId]);
  const modes = useResource(loadModes);
  // What stands in the way of submitting: one read, shown by the Findings and the Declaration, asked for again by each
  // change below, after a proposal and after a refused submit.
  const loadReadiness = useCallback(() => fetchReadiness(submissionId), [submissionId]);
  const readiness = useResource(loadReadiness);
  const reloadReadiness = readiness.reload;
  // A file that finishes its checks, and a Scan Report whose mode changed, change it too.
  useEffect(() => {
    if (files.data && modes.data) reloadReadiness();
  }, [files.data, modes.data, reloadReadiness]);
  // Counts the Findings entered by hand on this screen, so the Findings are asked for again after each.
  const [entered, setEntered] = useState(0);
  const editable = data ? isEditable(data.state) : false;

  return (
    <>
      <p>
        <AppLink path={SMO_AREA_PATH}>Back to your Systems</AppLink>
      </p>
      <SubmissionSummary submission={submission} />
      {data ? (
        <EditableContext.Provider value={editable}>
          <ScanReportsSection
            submissionId={submissionId}
            files={files}
            modes={modes}
            classification={classification}
            onFindingEntered={() => {
              setEntered((count) => count + 1);
              reloadReadiness();
            }}
            onScanDateConfirmed={reloadReadiness}
          />
          <FindingsSection
            submissionId={submissionId}
            files={files.data ?? []}
            entered={entered}
            readiness={readiness}
            classification={classification}
          />
          {editable ? (
            <DeclarationForm
              submission={data}
              readiness={readiness}
              classification={classification}
              onSubmitted={submission.reload}
            />
          ) : (
            <SubmittedDeclaration submission={data} />
          )}
        </EditableContext.Provider>
      ) : null}
    </>
  );
}
