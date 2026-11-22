import { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchFindings,
  fetchMatchingIds,
  MAX_BATCH_SIZE,
  type Finding,
  type FindingFilter,
  type NamedFile,
  type Readiness,
} from "../../api/findings.ts";
import type { Page } from "../../api/page.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import type { ReportFile } from "../../api/reportFiles.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { FilterChips, type Chip } from "../../components/FilterChips.tsx";
import { ProgressBar } from "../../components/ProgressBar.tsx";
import { useResource, type Resource } from "../../hooks/useResource.ts";
import { Editable } from "./Editable.tsx";
import { BulkBar } from "./BulkBar.tsx";
import { Pager } from "../shared/Pager.tsx";
import { FindingsTable } from "./FindingsTable.tsx";
import { SCAN_REPORTS } from "../shared/scanReports.ts";

const PAGE_SIZE = 100;

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

type ChipKey = "attention" | "all" | "disputed" | "criticalHigh";

/** The filter chips of Release 1 (story 36); Carried Over and Target Date Missed arrive with Release 2. */
const CHIPS: readonly Chip<ChipKey>[] = [
  { key: "attention", label: "Needs attention" },
  { key: "all", label: "All" },
  { key: "disputed", label: "Disputed" },
  { key: "criticalHigh", label: "Critical/High" },
];

/** Each chip is a filter the server applies, so the total and the pages are of the narrowed list. */
const FILTERS: Readonly<Record<ChipKey, FindingFilter>> = {
  attention: { needsAttention: true },
  all: {},
  disputed: { reviewState: "DISPUTED" },
  criticalHigh: { severity: ["CRITICAL", "HIGH"] },
};

/** The progress bar (story 38): how many Findings have a proposed status, and the counts that need a look. */
function ReadinessBar({ readiness }: { readiness: Readiness }) {
  return (
    <ProgressBar
      id="findings-progress"
      label="Findings with a proposed status"
      value={readiness.proposed}
      max={readiness.total}
    >
      {readiness.proposed} of {readiness.total} Findings have a proposed status; {readiness.unproposed} still to do.{" "}
      {readiness.awaitingReview} awaiting review, {readiness.disputed} Disputed.
      {readiness.carriedOver > 0 ? ` ${readiness.carriedOver} Carried Over.` : null}
      {readiness.targetDateMissed > 0 ? ` ${readiness.targetDateMissed} Target Date Missed.` : null}
    </ProgressBar>
  );
}

/** A report file named by its name and its Scan Report, as text. */
function fileWords(file: NamedFile): string {
  const report = SCAN_REPORTS.find((each) => each.type === file.reportType);
  return `${file.name} (${report ? report.title : file.reportType})`;
}

/**
 * The report files whose age stands in the way of submitting (story 26), as the server judged them at today's date: those
 * more than 90 days old, and those with no scan date to judge. Each is named.
 */
function FileAgeNotice({ readiness }: { readiness: Readiness }) {
  if (readiness.tooOld.length === 0 && readiness.undated.length === 0) return null;
  return (
    <div className="notice" role="status">
      {readiness.tooOld.length > 0 ? (
        <>
          <p>
            More than 90 days old, so the VA Submission cannot be submitted with them. Replace each with a newer scan,
            or correct its scan date on its Scan Report if the file has it wrong:
          </p>
          <ul>
            {readiness.tooOld.map((file) => (
              <li key={file.fileId}>{fileWords(file)}</li>
            ))}
          </ul>
        </>
      ) : null}
      {readiness.undated.length > 0 ? (
        <>
          <p>No scan date yet. Give the date each scan was made on its Scan Report:</p>
          <ul>
            {readiness.undated.map((file) => (
              <li key={file.fileId}>{fileWords(file)}</li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}

/** The progress bar and the files whose age stands in the way, once the server has said; or why it could not. */
function ReadinessPanel({ readiness }: { readiness: Resource<Readiness> }) {
  if (readiness.error) return <ErrorPanel problem={readiness.error.problem} />;
  if (!readiness.data) return null;
  return (
    <>
      <ReadinessBar readiness={readiness.data} />
      <FileAgeNotice readiness={readiness.data} />
    </>
  );
}

/** The ticked Findings, kept across pages of one chip and cleared when the chip changes or a batch is applied. */
function useSelection() {
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const toggle = useCallback((id: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }, []);
  const togglePage = useCallback((items: readonly Finding[]) => {
    setSelected((current) => {
      const next = new Set(current);
      const all = items.every((item) => next.has(item.id));
      for (const item of items) {
        if (all) next.delete(item.id);
        else next.add(item.id);
      }
      return next;
    });
  }, []);
  const replace = useCallback((ids: readonly string[]) => setSelected(new Set(ids)), []);
  const clear = useCallback(() => setSelected(new Set()), []);
  // A deleted Finding leaves the selection, so the bulk bar never names it.
  const forget = useCallback((id: string) => {
    setSelected((current) => {
      const next = new Set(current);
      next.delete(id);
      return next;
    });
  }, []);
  return { selected, toggle, togglePage, replace, clear, forget };
}

type Selection = ReturnType<typeof useSelection>;

/** "Select all that match": ticks every Finding of the chip, on every page, up to what one batch may hold. */
function SelectAllMatching({
  submissionId,
  filter,
  total,
  onSelected,
}: {
  submissionId: string;
  filter: FindingFilter;
  total: number;
  onSelected: (ids: readonly string[]) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function select() {
    setBusy(true);
    setProblem(undefined);
    try {
      onSelected(await fetchMatchingIds(submissionId, filter, MAX_BATCH_SIZE));
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Editable>
        <p>
          <button type="button" className="button button--secondary" disabled={busy} onClick={() => void select()}>
            {busy ? "Selecting…" : `Select all ${total} Findings that match`}
          </button>
        </p>
      </Editable>
      {problem ? <ErrorPanel problem={problem} /> : null}
    </>
  );
}

/** The page of Findings the chip shows, asked for again when a file is read or a Finding is entered by hand. */
function useFindings(
  submissionId: string,
  files: readonly ReportFile[],
  entered: number,
  filter: FindingFilter,
  page: number,
) {
  // A new file read into Findings changes the list: the key changes with the set of parsed files.
  const parsed = files
    .filter((file) => file.status === "PARSED")
    .map((file) => file.id)
    .sort()
    .join(",");
  const load = useCallback(() => {
    // `parsed` and `entered` are not arguments: they are why a new request is made when a file has been read into
    // Findings or a Finding was entered by hand.
    void parsed;
    void entered;
    return fetchFindings(submissionId, filter, page, PAGE_SIZE);
  }, [submissionId, filter, page, parsed, entered]);
  return useResource(load);
}

/** The list part of the section: what the chip shows, page by page, with the select-all of the whole chip. */
function FindingsList({
  submissionId,
  filter,
  chip,
  findings,
  page,
  onPage,
  selection,
  memos,
  classification,
  onProposed,
  onDeleted,
}: {
  submissionId: string;
  filter: FindingFilter;
  chip: ChipKey;
  findings: Resource<Page<Finding>>;
  page: number;
  onPage: (page: number) => void;
  selection: Selection;
  memos: readonly ReportFile[];
  classification: string;
  onProposed: () => void;
  onDeleted: (findingId: string) => void;
}) {
  const { data, error } = findings;
  if (!data) {
    return error ? <ErrorPanel problem={error.problem} /> : <p className="muted">Loading the Findings…</p>;
  }
  if (data.total === 0) {
    return <p>No Findings match. Choose another filter, or upload or enter the Scan Reports above.</p>;
  }
  const pages = Math.max(1, Math.ceil(data.total / data.size));
  const canSelectMore = data.total > data.items.length && selection.selected.size < data.total;
  return (
    <>
      {error ? <ErrorPanel problem={error.problem} /> : null}
      {canSelectMore ? (
        <SelectAllMatching
          key={chip}
          submissionId={submissionId}
          filter={filter}
          total={data.total}
          onSelected={selection.replace}
        />
      ) : null}
      <FindingsTable
        submissionId={submissionId}
        items={data.items}
        selected={selection.selected}
        onToggle={selection.toggle}
        onTogglePage={selection.togglePage}
        memos={memos}
        classification={classification}
        onProposed={onProposed}
        onDeleted={onDeleted}
      />
      {pages > 1 ? <Pager page={page} pages={pages} total={data.total} onPage={onPage} /> : null}
    </>
  );
}

/**
 * Keeps the page inside the list. A chip such as Needs attention shrinks when a status is proposed, so after a reload the
 * page asked for can be past the end; the server then answers no items, and the page moves to the last one there is.
 */
function useClampedPage(data: Page<Finding> | undefined, page: number, setPage: (page: number) => void) {
  useEffect(() => {
    if (data && data.page === page && page > 0 && data.items.length === 0 && data.total > 0) {
      setPage(Math.ceil(data.total / data.size) - 1);
    }
  }, [data, page, setPage]);
}

/**
 * The Findings read from the Scan Reports (stories 28, 35 to 38), as a spreadsheet-style list, the most severe first: filter
 * chips the server applies, a tick box on each row with a select-all, a select on each row to propose a Finding Status for
 * that Finding, a sticky bulk bar that applies one status to every ticked Finding, and a progress bar. The list is asked
 * for again when a file has been read into Findings (a file becomes PARSED), and the list and the progress after each
 * proposal.
 * The approval memo of a Risk Accepted proposal is chosen among the supporting evidence files that have finished their
 * checks.
 */
export function FindingsSection({
  submissionId,
  files,
  entered,
  readiness,
  classification,
}: {
  submissionId: string;
  files: readonly ReportFile[];
  /** How many Findings were entered by hand on this screen: a new one is why the list is asked for again. */
  entered: number;
  /** The screen's readiness, shared with the Declaration; asked for again here after each proposal. */
  readiness: Resource<Readiness>;
  classification: string;
}) {
  const [chip, setChip] = useState<ChipKey>("attention");
  const [page, setPage] = useState(0);
  const selection = useSelection();
  // Only this submission's files, as the Scan Report cards show.
  const ofThisSubmission = files.filter((file) => file.submissionId === submissionId);
  const filter = FILTERS[chip];
  const findings = useFindings(submissionId, ofThisSubmission, entered, filter, page);
  const reload = () => {
    findings.reload();
    readiness.reload();
  };
  const memos = ofThisSubmission.filter((file) => file.kind === "EVIDENCE" && file.status === "READY");
  useClampedPage(findings.data, page, setPage);
  // After a batch is applied the bulk bar goes away; the focus goes to the heading, above the new counts.
  const heading = useRef<HTMLHeadingElement>(null);

  const choose = (key: ChipKey) => {
    setChip(key);
    setPage(0);
    selection.clear();
  };

  return (
    <section className="card" aria-labelledby="findings">
      <h3 id="findings" ref={heading} tabIndex={-1}>
        Findings
      </h3>
      <p className="muted">
        Every Finding needs a status before you can submit. Choose one on its row and give what it requires, or tick
        several and apply one status to them all; the Cyber Manager then confirms it or sends it back.
      </p>
      <ReadinessPanel readiness={readiness} />
      <FilterChips label="Show Findings" chips={CHIPS} selected={chip} onSelect={choose} />
      <FindingsList
        submissionId={submissionId}
        filter={filter}
        chip={chip}
        findings={findings}
        page={page}
        onPage={setPage}
        selection={selection}
        memos={memos}
        classification={classification}
        onProposed={reload}
        onDeleted={(findingId) => {
          // The deleted Finding's row goes with its buttons: the focus goes to the heading, above the new counts.
          selection.forget(findingId);
          reload();
          heading.current?.focus();
        }}
      />
      {selection.selected.size > 0 ? (
        <Editable>
          <BulkBar
            submissionId={submissionId}
            selectedIds={[...selection.selected]}
            memos={memos}
            classification={classification}
            onApplied={() => {
              selection.clear();
              reload();
              heading.current?.focus();
            }}
            onClear={selection.clear}
          />
        </Editable>
      ) : null}
    </section>
  );
}
