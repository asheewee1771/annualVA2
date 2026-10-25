import { useCallback, useEffect, useState } from "react";
import { fetchReadiness } from "../../api/findings.ts";
import { fetchReportFiles } from "../../api/reportFiles.ts";
import { fetchScanReportModes } from "../../api/scanReports.ts";
import { fetchSubmission, isEditable, type AcceptedDeclaration, type Submission } from "../../api/submissions.ts";
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
 * While an Exemption request waits on a VA POC, the VA Submission is frozen (story 44); once approved, it is Exempted,
 * and an Exemption on a scheduled VA shows where it stands: to record, to confirm, confirmed or lapsed (story 47).
 */
function ExemptionNotice({
  submission,
  classification,
  onRecorded,
}: {
  submission: Submission | undefined;
  classification: string;
  onRecorded: () => void;
}) {
  const conditional = submission?.conditionalExemption;
  if (submission?.state === "EXEMPTION_REQUESTED") {
    return (
      <div className="notice" role="status">
        <p>
          Frozen: your Exemption request is waiting on a VA POC&apos;s decision. Nothing can be changed or submitted
          until then. If it is rejected, this VA Submission goes back exactly as it was.
        </p>
      </div>
    );
  }
  return (
    <>
      {submission?.state === "EXEMPTED" ? (
        <div className="notice" role="status">
          <p>Exempted: a VA POC approved the Exemption. The work below is kept for audit, read only.</p>
        </div>
      ) : null}
      {conditional ? (
        <ConditionalExemptionPanel exemption={conditional} classification={classification} onRecorded={onRecorded} />
      ) : null}
    </>
  );
}

/**
 * Where the SMO/Tech Lead prepares a VA Submission (stories 19, 20, 23, 24, 25, 26, 28, 35, 40 and 41): the three Scan
 * Report cards with how each is provided, their files and each file's pipeline status (asked for again while a file is
 * still being checked), the scan date and target of each report file and the form to enter a Finding by hand, the Findings
 * read from them or entered with a status to propose for each, and the Declaration with Submit. Once Returned for Rework,
 * it points to the rework queue (story 60). Once submitted, the screen is read-only. The state, the dates and what stands
 * in the way of submitting are the server's.
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
      <ReworkNotice submission={data} />
      <ExemptionNotice submission={data} classification={classification} onRecorded={submission.reload} />
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
