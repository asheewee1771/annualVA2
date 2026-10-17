import { useCallback } from "react";
import { dayLabel } from "../../api/cycles.ts";
import { fetchReportFiles } from "../../api/reportFiles.ts";
import { fetchSubmission, type SubmissionState } from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { FindingsSection } from "./FindingsSection.tsx";
import { SMO_AREA_PATH } from "./paths.ts";
import { ScanReportsSection } from "./ScanReportsSection.tsx";

const STATE_LABELS: Readonly<Record<SubmissionState, string>> = { DRAFT: "Draft" };

/** The Declaration stays off until the submit command arrives with its own ticket; it is shown so the order of the work is. */
function DeclarationSection({ classification }: { classification: string }) {
  return (
    <section className="card" aria-labelledby="declaration">
      <h3 id="declaration">Declaration</h3>
      <p className="muted">
        You can accept the Declaration and submit for review once every Scan Report is in and every Finding has a
        proposed status.
      </p>
      <ClassifiedField label="I accept the Declaration" classification={classification} id="declaration-accepted">
        <input id="declaration-accepted" type="checkbox" disabled />
      </ClassifiedField>
      <p>
        <button type="button" className="button" disabled>
          Submit for review
        </button>
      </p>
    </section>
  );
}

/**
 * Where the SMO/Tech Lead prepares a VA Submission (stories 19, 20, 24, 28 and 35): the three Scan Report cards with their
 * files and each file's pipeline status (asked for again while a file is still being checked), the Findings read from them
 * with a status to propose for each, and the Declaration, disabled until the submit command arrives. The state and the
 * dates are the server's.
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
  const { data, error } = submission;
  const loadFiles = useCallback(() => fetchReportFiles(submissionId), [submissionId]);
  const files = useResource(loadFiles);

  return (
    <>
      <p>
        <AppLink path={SMO_AREA_PATH}>Back to your Systems</AppLink>
      </p>
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
            <p className="muted">
              Submit by {dayLabel(data.submitDue)}, reviewed by {dayLabel(data.reviewDue)}, endorsed by{" "}
              {dayLabel(data.endorseDue)}. The windows are soft: nothing locks when one passes.
            </p>
          </>
        ) : null}
      </div>
      {data ? (
        <>
          <ScanReportsSection submissionId={submissionId} files={files} classification={classification} />
          <FindingsSection submissionId={submissionId} files={files.data ?? []} classification={classification} />
          <DeclarationSection classification={classification} />
        </>
      ) : null}
    </>
  );
}
