import { dayLabel } from "../../api/cycles.ts";
import type { Submission } from "../../api/submissions.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import type { Resource } from "../../hooks/useResource.ts";
import { STATE_LABELS } from "../shared/systemStates.ts";

export const WHEN = new Intl.DateTimeFormat("en-SG", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "Asia/Singapore",
});

/** The VA Submission's state and its dates, as the server gives them, under the screen's heading. */
export function SubmissionSummary({ heading, submission }: { heading: string; submission: Resource<Submission> }) {
  const { data, error } = submission;
  return (
    <div className="card">
      <h2>{heading}</h2>
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
