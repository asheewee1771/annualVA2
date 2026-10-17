import { useCallback, useState } from "react";
import { fetchFindings } from "../../api/findings.ts";
import type { ReportFile } from "../../api/reportFiles.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { FindingRow } from "./FindingRow.tsx";

const PAGE_SIZE = 100;

/**
 * The Findings read from the Scan Reports (stories 28 and 35), as a spreadsheet-style list, the most severe first, with a
 * select on each row to propose a Finding Status and the fields that status requires. It is asked for again when a file has
 * been read into Findings (a file becomes PARSED) and after each proposal. The approval memo of a Risk Accepted proposal is
 * chosen among the supporting evidence files that have finished their checks.
 */
export function FindingsSection({
  submissionId,
  files,
  classification,
}: {
  submissionId: string;
  files: readonly ReportFile[];
  classification: string;
}) {
  const [page, setPage] = useState(0);
  // Only this submission's files, as the Scan Report cards show.
  const ofThisSubmission = files.filter((file) => file.submissionId === submissionId);
  // A new file read into Findings changes the list: the key changes with the set of parsed files.
  const parsed = ofThisSubmission
    .filter((file) => file.status === "PARSED")
    .map((file) => file.id)
    .sort()
    .join(",");
  const load = useCallback(() => {
    // `parsed` is not an argument: it is why a new request is made when a file has been read into Findings.
    void parsed;
    return fetchFindings(submissionId, page, PAGE_SIZE);
  }, [submissionId, page, parsed]);
  const findings = useResource(load);
  const { data, error } = findings;
  const memos = ofThisSubmission.filter((file) => file.kind === "EVIDENCE" && file.status === "READY");
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <section className="card" aria-labelledby="findings">
      <h3 id="findings">Findings</h3>
      <p className="muted">
        Every Finding needs a status before you can submit. Choose one on its row and give what it requires; the Cyber
        Manager then confirms it or sends it back.
      </p>
      {error ? <ErrorPanel problem={error.problem} /> : null}
      {findings.loading && !data ? <p className="muted">Loading the Findings…</p> : null}
      {data && data.total === 0 ? <p>No Findings have been read yet. Upload the Scan Reports above.</p> : null}
      {data && data.items.length > 0 ? (
        <div className="table-scroll">
          <table className="data-table">
            <caption className="visually-hidden">Findings of this VA Submission</caption>
            <thead>
              <tr>
                <th scope="col">Severity</th>
                <th scope="col">Finding</th>
                <th scope="col">Package or rule</th>
                <th scope="col">Scan Report</th>
                <th scope="col">Places</th>
                <th scope="col">Status</th>
                <th scope="col">Propose</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((finding) => (
                <FindingRow
                  key={finding.id}
                  finding={finding}
                  memos={memos}
                  classification={classification}
                  onProposed={findings.reload}
                />
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {data && pages > 1 ? (
        <div className="pager">
          <button
            type="button"
            className="button button--secondary"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous page
          </button>
          <span aria-live="polite">
            Page {page + 1} of {pages} ({data.total} Findings)
          </span>
          <button
            type="button"
            className="button button--secondary"
            disabled={page + 1 >= pages}
            onClick={() => setPage(page + 1)}
          >
            Next page
          </button>
        </div>
      ) : null}
    </section>
  );
}
