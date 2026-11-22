import { useEffect, useRef } from "react";
import type { Finding } from "../../api/findings.ts";
import type { ReportFile } from "../../api/reportFiles.ts";
import { Editable } from "./Editable.tsx";
import { FindingRow } from "./FindingRow.tsx";

/**
 * One page of the Findings as a spreadsheet-style table: a tick box on each row, and one in the header that ticks or clears
 * every row of the page.
 */
export function FindingsTable({
  submissionId,
  items,
  selected,
  onToggle,
  onTogglePage,
  memos,
  classification,
  onProposed,
  onDeleted,
}: {
  submissionId: string;
  items: readonly Finding[];
  selected: ReadonlySet<string>;
  onToggle: (findingId: string) => void;
  onTogglePage: (items: readonly Finding[]) => void;
  memos: readonly ReportFile[];
  classification: string;
  onProposed: () => void;
  onDeleted: (findingId: string) => void;
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
              <Editable>
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
              </Editable>
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
              submissionId={submissionId}
              finding={finding}
              selected={selected.has(finding.id)}
              onToggle={onToggle}
              memos={memos}
              classification={classification}
              onProposed={onProposed}
              onDeleted={onDeleted}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}
