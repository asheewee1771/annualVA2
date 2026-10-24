import { useEffect, useRef, useState } from "react";
import { proposeStatus, type Proposal, type ReworkItem } from "../../api/findings.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import type { ReportFile } from "../../api/reportFiles.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import {
  inputFor,
  proposalDetail,
  SEVERITY_LABELS,
  severityClass,
  statusSpec,
  STATUSES,
  type FieldName,
  type StatusSpec,
} from "../shared/findingStatuses.ts";
import { reportTitle } from "../shared/scanReports.ts";
import { ProposalFieldInput } from "./ProposalFieldInput.tsx";
import { WHEN } from "./SubmissionSummary.tsx";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

type Values = Partial<Record<FieldName, string>>;

/** A key typed in a field is text (or the field's own), never a shortcut. */
function inField(target: EventTarget | null): target is HTMLElement {
  return (
    target instanceof HTMLElement && (target.isContentEditable || target.closest("input, textarea, select") !== null)
  );
}

/** Enter on a button or link activates it, so it is left to it. */
function onControl(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && target.closest("button, a") !== null;
}

/** The status a key picks: 1 to 4 in the order of the statuses; undefined for any other key. */
function statusIndexOf(key: string): number | undefined {
  const digit = Number(key);
  return Number.isInteger(digit) && digit >= 1 && digit <= STATUSES.length ? digit - 1 : undefined;
}

/** What a key held with Ctrl, Alt or Cmd, or already handled, is left to the browser. */
function ignored(event: KeyboardEvent): boolean {
  return event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey;
}

/**
 * Keys 1 to 4 pick a status and Enter saves (story 62), only while no field has the key (and, for Enter, no button or
 * link); Esc leaves a field so the keys work again.
 */
function useReworkKeys(enabled: boolean, pick: (index: number) => void, save: () => void) {
  useEffect(() => {
    if (!enabled) return undefined;
    function onKey(event: KeyboardEvent) {
      if (ignored(event)) return;
      if (inField(event.target)) {
        if (event.key === "Escape") event.target.blur();
        return;
      }
      const index = statusIndexOf(event.key);
      if (index !== undefined) {
        event.preventDefault();
        pick(index);
      } else if (event.key === "Enter" && !event.repeat && !onControl(event.target)) {
        event.preventDefault();
        save();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [enabled, pick, save]);
}

/** The fields of a status as the current proposal has them, when it is of that status; otherwise empty. */
function valuesOf(spec: StatusSpec, proposal: Proposal | null): Values {
  if (!proposal || proposal.status !== spec.value) return {};
  const values: Values = {};
  for (const field of spec.fields) values[field.name] = proposal[field.name] ?? "";
  return values;
}

/** What the Cyber Manager said when they Disputed it, as text. */
function DisputeNote({ item }: { item: ReworkItem }) {
  if (!item.dispute) return null;
  return (
    <div className="notice notice--refused">
      <p>
        <strong>Disputed</strong> by the Cyber Manager on {WHEN.format(new Date(item.dispute.reviewedAt))}:
      </p>
      {item.dispute.comment !== null ? <p className="finding-detail">{item.dispute.comment}</p> : null}
    </div>
  );
}

/** What is proposed for the Finding now, or that nothing is. */
function CurrentProposal({ proposal }: { proposal: Proposal | null }) {
  if (!proposal) {
    return (
      <p>
        <span className="flag flag--warning">No status proposed</span>
      </p>
    );
  }
  return (
    <p>
      Proposed now: <strong>{statusSpec(proposal.status).label}</strong>
      <span className="muted finding-detail"> · {proposalDetail(proposal)}</span>
    </p>
  );
}

/** The Finding as the report or the SMO/Tech Lead named it, and what is proposed for it now. */
function FindingFacts({ item }: { item: ReworkItem }) {
  const { finding } = item;
  return (
    <>
      <p>
        <span className={severityClass(finding.severity)}>{SEVERITY_LABELS[finding.severity]}</span>{" "}
        <span className="mono">{finding.identifier}</span> · {reportTitle(finding.reportType)}
        {finding.source === "MANUAL" ? (
          <>
            {" "}
            <span className="flag flag--warning">Manually Entered</span>
          </>
        ) : null}
      </p>
      {finding.title !== null ? <p className="finding-detail">{finding.title}</p> : null}
      <p className="mono finding-detail">{finding.packageOrRule}</p>
      <p className="muted">
        Found in {finding.occurrenceCount} {finding.occurrenceCount === 1 ? "place" : "places"}
      </p>
      <CurrentProposal proposal={finding.currentProposal} />
    </>
  );
}

/** The four statuses as buttons, each with the key that picks it. */
function StatusButtons({
  chosen,
  busy,
  onPick,
}: {
  chosen: StatusSpec | undefined;
  busy: boolean;
  onPick: (index: number) => void;
}) {
  return (
    <div className="chips" role="group" aria-label="Pick a status">
      {STATUSES.map((spec, index) => (
        <button
          key={spec.value}
          type="button"
          className={spec.value === chosen?.value ? "chip chip--on" : "chip"}
          aria-pressed={spec.value === chosen?.value}
          aria-keyshortcuts={String(index + 1)}
          disabled={busy}
          onClick={() => onPick(index)}
        >
          <kbd>{index + 1}</kbd> {spec.label}
        </button>
      ))}
    </div>
  );
}

/** The fields the chosen status requires, the server's refusal, and Save; the first field takes the keys when it opens. */
function StatusFields({
  idPrefix,
  chosen,
  values,
  problem,
  busy,
  memos,
  classification,
  onChange,
  onSave,
}: {
  idPrefix: string;
  chosen: StatusSpec;
  values: Values;
  problem: Problem | undefined;
  busy: boolean;
  memos: readonly ReportFile[];
  classification: string;
  onChange: (name: FieldName, value: string) => void;
  onSave: () => void;
}) {
  const fields = useRef<HTMLDivElement>(null);
  useEffect(() => {
    fields.current?.querySelector<HTMLElement>("textarea, input, select")?.focus();
  }, [chosen]);
  return (
    <div ref={fields}>
      {chosen.fields.map((field) => (
        <ProposalFieldInput
          key={`${chosen.value}-${field.name}`}
          idPrefix={idPrefix}
          spec={field}
          value={values[field.name] ?? ""}
          invalid={problem?.field === field.name}
          memos={memos}
          classification={classification}
          onChange={onChange}
        />
      ))}
      {problem ? <ErrorPanel problem={problem} /> : null}
      <p>
        <button type="button" className="button" disabled={busy} aria-keyshortcuts="Enter" onClick={onSave}>
          {busy ? "Saving…" : `Save ${chosen.label} and go to the next`}
        </button>
      </p>
    </div>
  );
}

/**
 * One Finding of the rework queue (stories 60, 61 and 62): what it is, the Cyber Manager's Dispute as text, what is
 * proposed now, and the four statuses as buttons bound to keys 1 to 4. Picking the status already proposed starts from
 * what was written, so a Dispute is answered by changing only what it asked about. Save (or Enter) proposes it and the
 * queue moves on; the server checks every field again and names the one it refuses.
 */
export function ReworkFinding({
  item,
  memos,
  classification,
  editable,
  onSaved,
}: {
  item: ReworkItem;
  memos: readonly ReportFile[];
  classification: string;
  editable: boolean;
  onSaved: () => void;
}) {
  const { finding } = item;
  const [chosen, setChosen] = useState<StatusSpec | undefined>(undefined);
  const [values, setValues] = useState<Values>({});
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  function pick(index: number) {
    const spec = STATUSES[index];
    if (!spec || spec.value === chosen?.value) return;
    setChosen(spec);
    setValues(valuesOf(spec, finding.currentProposal));
    setProblem(undefined);
  }

  async function save() {
    if (!chosen || busy) return;
    setBusy(true);
    setProblem(undefined);
    try {
      await proposeStatus(finding.id, inputFor(chosen, values));
      setChosen(undefined);
      onSaved();
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  useReworkKeys(editable, pick, () => void save());

  return (
    <section className="card" aria-labelledby="rework-finding">
      <h3 id="rework-finding">This Finding</h3>
      <FindingFacts item={item} />
      <DisputeNote item={item} />
      {editable ? <StatusButtons chosen={chosen} busy={busy} onPick={pick} /> : null}
      {editable && chosen ? (
        <StatusFields
          idPrefix={`rework-${finding.id}`}
          chosen={chosen}
          values={values}
          problem={problem}
          busy={busy}
          memos={memos}
          classification={classification}
          onChange={(name, value) => setValues((current) => ({ ...current, [name]: value }))}
          onSave={() => void save()}
        />
      ) : null}
    </section>
  );
}
