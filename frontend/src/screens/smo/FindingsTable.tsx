import { useEffect, useRef } from "react";
import type { Finding } from "../../api/findings.ts";
import type { ReportFile } from "../../api/reportFiles.ts";
import { FindingRow } from "./FindingRow.tsx";

/**
 * One page of the Findings as a spreadsheet-style table: a tick box on each row, and one in the header that ticks or clears
 * every row of the page.
 */
export function FindingsTable({
  items,
  selected,
  onToggle,
  onTogglePage,
  memos,
  classification,
  onProposed,
}: {
  items: readonly Finding[];
  selected: ReadonlySet<string>;
  onToggle: (findingId: string) => void;
  onTogglePage: (items: readonly Finding[]) => void;
  memos: readonly ReportFile[];
  classification: string;
  onProposed: () => void;
}) {
  const ticked = items.filter((item) => selected.has(item.id)).length;
  const pageTicked = items.length > 0 && ticked === items.length;
  // A page partly ticked shows the box as neither ticked nor clear; the property has no attribute, so it is set here.
  const pageBox = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (pageBox.current) pageBox.current.indeterminate = ticked > 0 && !pageTicked;
  }, [ticked, pageTicked]);
  return (
    <div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">Findings of this VA Submission</caption>
        <thead>
          <tr>
            <th scope="col">
              <input
                id="findings-tick-page"
                ref={pageBox}
                type="checkbox"
                checked={pageTicked}
                onChange={() => onTogglePage(items)}
              />
              <label htmlFor="findings-tick-page" className="visually-hidden">
                Select every Finding on this page
              </label>
            </th>
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
          {items.map((finding) => (
            <FindingRow
              key={finding.id}
              finding={finding}
              selected={selected.has(finding.id)}
              onToggle={onToggle}
              memos={memos}
              classification={classification}
              onProposed={onProposed}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Previous and next page, with where the reader is. */
export function Pager({
  page,
  pages,
  total,
  onPage,
}: {
  page: number;
  pages: number;
  total: number;
  onPage: (page: number) => void;
}) {
  return (
    <div className="pager">
      <button type="button" className="button button--secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Previous page
      </button>
      <span aria-live="polite">
        Page {page + 1} of {pages} ({total} Findings)
      </span>
      <button
        type="button"
        className="button button--secondary"
        disabled={page + 1 >= pages}
        onClick={() => onPage(page + 1)}
      >
        Next page
      </button>
    </div>
  );
}
