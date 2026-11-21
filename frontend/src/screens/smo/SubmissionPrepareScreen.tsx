import { useCallback, useEffect, useRef, useState } from "react";
import { fetchReadiness } from "../../api/findings.ts";
import { fetchReportFiles } from "../../api/reportFiles.ts";
import { fetchScanReportModes } from "../../api/scanReports.ts";
import {
  fetchSubmission,
  isEditable,
  type AcceptedDeclaration,
  type EditableState,
  type Submission,
  type SubmissionState,
} from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { ConditionalExemptionPanel } from "./ConditionalExemptionPanel.tsx";
import { DeclarationForm } from "./DeclarationForm.tsx";
import { EditableContext } from "./Editable.tsx";
import { FindingsSection } from "./FindingsSection.tsx";
import { reworkPath, SMO_AREA_PATH } from "./paths.ts";
import { ScanReportsSection } from "./ScanReportsSection.tsx";
import { SubmissionSummary, WHEN } from "./SubmissionSummary.tsx";

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

/** Once Returned for Rework, the way to the rework queue (story 60); nothing otherwise. */
function ReworkNotice({ submission }: { submission: Submission | undefined }) {
  if (submission?.state !== "RETURNED_FOR_REWORK") return null;
  return (
    <div className="notice" role="status">
      <p>
        The Cyber Manager returned this VA Submission for rework.{" "}
        <AppLink path={reworkPath(submission.id)}>Work through the rework queue</AppLink>, one Finding at a time, with
        what was Disputed and why.
      </p>
    </div>
  );
}

/**
 * Why a VA Submission cannot be changed, one sentence for each state that `isEditable` refuses: the table does not
 * compile when one lacks a sentence, or when the rule changes. Whether a sentence is shown is not decided here: the
 * screen decides it once, with `isEditable`.
 */
const READ_ONLY_REASONS: Readonly<Partial<Record<SubmissionState, string>>> = {
  NOT_STARTED: "It has not been started: start it from your Systems to prepare it.",
  IN_REVIEW:
    "It was submitted for review and is with the Cyber Manager. If it is returned for rework, you can change it again.",
  AWAITING_ENDORSEMENT:
    "It was submitted and its Findings are all confirmed; it is now with the VA POCs for endorsement. If it is returned for rework, you can change it again.",
  EXEMPTION_REQUESTED:
    "It is frozen: your Exemption request is waiting on a VA POC's decision, and nothing can be changed or submitted until then. If it is rejected, this VA Submission goes back exactly as it was.",
  EXEMPTED: "It is decided: a VA POC approved the Exemption. The work below is kept for audit.",
  ENDORSED: "It is decided: a VA POC endorsed it. The work below is kept for audit.",
  NOT_ENDORSED: "It is decided: its VA Cycle closed before it was endorsed. The work below is kept for audit.",
  WITHDRAWN: "It is withdrawn: its System was retired before endorsement. The work below is kept for audit.",
} satisfies Record<Exclude<SubmissionState, EditableState>, string>;

/**
 * An Exemption approved on a scheduled VA is not final yet: its VA is still to be recorded or confirmed, in the panel
 * just below the notice, and it lapses if it is not (ADR 0051). So it does not say "decided".
 */
const CONDITIONAL_EXEMPTION_REASON =
  "A VA POC approved the Exemption on a scheduled VA, so it stays conditional until that VA is recorded and confirmed: where that stands is just below. The work prepared here is kept for audit and cannot be changed.";

function readOnlyReason(submission: Submission): string | undefined {
  if (submission.state === "EXEMPTED" && submission.conditionalExemption?.conditional) {
    return CONDITIONAL_EXEMPTION_REASON;
  }
  return READ_ONLY_REASONS[submission.state];
}

/**
 * Near the top, while the VA Submission cannot be changed: that it is read-only and why (ticket 65). `editable` is the
 * screen's one decision, the same one that disables every control in an {@link Editable}. When the screen turns
 * read-only while it is open (after "Submit for review"), the focus goes to the notice: the Submit button that held it
 * is gone, and a screen reader then reads why.
 */
function ReadOnlyNotice({ submission, editable }: { submission: Submission | undefined; editable: boolean }) {
  const notice = useRef<HTMLDivElement>(null);
  // Only a change from editable to read-only moves the focus. A VA Submission that opens read-only was never editable
  // here (`editable` is false until it has loaded), so it does not take the focus.
  const wasEditable = useRef(false);
  useEffect(() => {
    if (wasEditable.current && !editable) notice.current?.focus();
    wasEditable.current = editable;
  }, [editable]);

  if (!submission || editable) return null;
  return (
    <div className="notice" role="status" ref={notice} tabIndex={-1}>
      <p>
        <strong>Read-only.</strong> {readOnlyReason(submission)} Downloads, filters and paging still work.
      </p>
    </div>
  );
}

/**
 * An Exemption on a scheduled VA shows where it stands: to record, to confirm, confirmed or lapsed (story 47). Not while
 * a new Exemption request waits on a VA POC: the VA Submission is frozen then, and the notice above says so.
 */
function ConditionalExemptionNotice({
  submission,
  classification,
  onRecorded,
}: {
  submission: Submission | undefined;
  classification: string;
  onRecorded: () => void;
}) {
  const conditional = submission?.conditionalExemption;
  if (!conditional || submission.state === "EXEMPTION_REQUESTED") return null;
  return <ConditionalExemptionPanel exemption={conditional} classification={classification} onRecorded={onRecorded} />;
}

/**
 * Where the SMO/Tech Lead prepares a VA Submission (stories 19, 20, 23, 24, 25, 26, 28, 35, 40 and 41): the three Scan
 * Report cards with how each is provided, their files and each file's pipeline status (asked for again while a file is
 * still being checked), the scan date and target of each report file and the form to enter a Finding by hand, the Findings
 * read from them or entered with a status to propose for each, and the Declaration with Submit. Once Returned for Rework,
 * it points to the rework queue (story 60). Once submitted, the screen is read-only and says so near the top, with
 * why (ticket 65). The state, the dates and what stands in the way of submitting are the server's.
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
      <SubmissionSummary heading="Prepare the VA Submission" submission={submission} />
      <ReadOnlyNotice submission={data} editable={editable} />
      <ReworkNotice submission={data} />
      <ConditionalExemptionNotice submission={data} classification={classification} onRecorded={submission.reload} />
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
