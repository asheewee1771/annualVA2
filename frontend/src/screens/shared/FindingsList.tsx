import { useCallback, useState, type ReactNode } from "react";
import type { Page } from "../../api/page.ts";
import type { Finding } from "../../api/findings.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { FindingCells } from "./FindingCells.tsx";
import { Pager } from "./Pager.tsx";

/** How many Findings one page of a reader's list shows. */
export const FINDINGS_PAGE_SIZE = 100;

/** What one row's last cell shows: the review, and any controls the reader has. */
type ReviewCell = (finding: Finding) => ReactNode;

/** A table of Findings: what each is, what was proposed, and the reader's review cell. */
function FindingsTable({
  findings,
  caption,
  reviewCell,
}: {
  findings: readonly Finding[];
  caption: string;
  reviewCell: ReviewCell;
}) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Severity</th>
            <th scope="col">Finding</th>
            <th scope="col">Proposed status</th>
            <th scope="col">Review</th>
          </tr>
        </thead>
        <tbody>
          {findings.map((finding) => (
            <tr key={finding.id}>
              <FindingCells finding={finding} />
              <td>{reviewCell(finding)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * One page of the list: the table, or that this page has none, and the pager whenever there is more than one page (so a
 * page left empty when the list shrank after a review can still be left).
 */
function FindingsPageView({
  result,
  caption,
  reviewCell,
  onPage,
}: {
  result: Page<Finding>;
  caption: string;
  reviewCell: ReviewCell;
  onPage: (page: number) => void;
}) {
  return (
    <>
      {result.items.length === 0 ? (
        <p className="muted">There are none here.</p>
      ) : (
        <FindingsTable findings={result.items} caption={caption} reviewCell={reviewCell} />
      )}
      {result.total > FINDINGS_PAGE_SIZE ? (
        <Pager
          page={result.page}
          pages={Math.ceil(result.total / FINDINGS_PAGE_SIZE)}
          total={result.total}
          onPage={onPage}
        />
      ) : null}
    </>
  );
}

/**
 * A page at a time of a VA Submission's Findings, the most severe first, read again whenever `refreshKey` changes: the
 * heading with how many there are, then each Finding with what was proposed and the reader's review cell. Everything a
 * report or a person gave is shown as text.
 *
 * @param load reads one page (of `FINDINGS_PAGE_SIZE`); it must be stable across renders (`useCallback`)
 */
export function FindingsList({
  load,
  refreshKey,
  heading,
  caption,
  reviewCell,
}: {
  load: (page: number) => Promise<Page<Finding>>;
  refreshKey: number;
  /** The heading, followed by how many Findings there are. */
  heading: string;
  caption: string;
  reviewCell: ReviewCell;
}) {
  const [page, setPage] = useState(0);
  const loadPage = useCallback(() => load(page), [load, page]);
  const findings = useResource(loadPage, refreshKey);
  const result = findings.data;
  return (
    <>
      <p>
        <strong>
          {heading} ({result ? result.total : 0})
        </strong>
      </p>
      {findings.error ? <ErrorPanel problem={findings.error.problem} /> : null}
      {findings.loading && !result ? <p className="muted">Loading the Findings…</p> : null}
      {result ? <FindingsPageView result={result} caption={caption} reviewCell={reviewCell} onPage={setPage} /> : null}
    </>
  );
}
