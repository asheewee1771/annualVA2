import { useCallback } from "react";
import { dayLabel } from "../../api/cycles.ts";
import { fetchSubmission, type SubmissionState } from "../../api/submissions.ts";
import { AppLink } from "../../app/AppLink.tsx";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { SMO_AREA_PATH } from "./paths.ts";

const STATE_LABELS: Readonly<Record<SubmissionState, string>> = { DRAFT: "Draft" };

interface ScanReportCard {
  type: string;
  title: string;
  note: string;
}

/** The three Scan Reports every VA Submission holds (CONTEXT.md); Secure Configuration is always required. */
const SCAN_REPORTS: readonly ScanReportCard[] = [
  {
    type: "dependency-check",
    title: "OWASP Dependency-Check",
    note: "Required, unless you mark it not applicable with a justification the Cyber Manager confirms.",
  },
  {
    type: "secure-configuration",
    title: "Secure Configuration",
    note: "Always required: a SCAP ARF or XCCDF result, or a Nessus export.",
  },
  {
    type: "burp-suite",
    title: "Burp Suite",
    note: "Required, unless you mark it not applicable with a justification the Cyber Manager confirms.",
  },
];

/** One Scan Report with nothing uploaded yet: uploading and reading Findings arrive with their own tickets. */
function EmptyScanReportCard({ report }: { report: ScanReportCard }) {
  return (
    <section className="card" aria-labelledby={`report-${report.type}`}>
      <h3 id={`report-${report.type}`}>{report.title}</h3>
      <p className="muted">{report.note}</p>
      <p>No files uploaded yet.</p>
    </section>
  );
}

/** The Declaration stays off until every Finding has a proposed status; there are no Findings to propose on yet. */
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
 * Where the SMO/Tech Lead prepares a VA Submission (story 19): the three Scan Report cards, empty until files can be
 * uploaded, and the Declaration, disabled until there is something to declare. The state and the dates are the server's.
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
          {SCAN_REPORTS.map((report) => (
            <EmptyScanReportCard key={report.type} report={report} />
          ))}
          <DeclarationSection classification={classification} />
        </>
      ) : null}
    </>
  );
}
