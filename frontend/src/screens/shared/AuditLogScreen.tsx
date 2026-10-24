import { useCallback, useState, type FormEvent } from "react";
import {
  AUDIT_ACTIONS,
  NO_FILTERS,
  PAGE_SIZE,
  actionLabel,
  fetchAuditEntries,
  type AuditEntry,
  type AuditFilters,
  type AuditPage,
} from "../../api/audit.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";

const UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

/** Business dates and times are shown in Singapore time, whatever the browser's own zone is. */
const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "medium", timeZone: "Asia/Singapore" });

function when(instant: string): string {
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? instant : WHEN.format(date);
}

const VA_POC_INTRO =
  "Every sign-in, sign-out, refused request and action, newest first. Entries cannot be changed or deleted.";

/**
 * The audit log (story 84): who did what, when, and with what outcome, filtered and paged by the server. It shows
 * ids and fixed codes only; a Finding appears as an id and never with its detail. Everything is rendered as text. The
 * server decides which entries the reader may see: a VA POC every entry, a Cyber Manager the history of the VA
 * Submissions they reviewed (story 85), so the same screen serves both with its own heading and starting filters.
 */
export function AuditLogScreen({
  classification,
  title = "Audit log",
  intro = VA_POC_INTRO,
  initial = NO_FILTERS,
}: {
  classification: string;
  title?: string;
  intro?: string;
  initial?: AuditFilters;
}) {
  const [applied, setApplied] = useState<AuditFilters>(initial);
  const [page, setPage] = useState(0);
  const load = useCallback(() => fetchAuditEntries(applied, page), [applied, page]);
  const log = useResource(load);

  function apply(filters: AuditFilters) {
    setApplied(filters);
    setPage(0);
  }

  return (
    <div className="card">
      <h2>{title}</h2>
      <p className="muted">{intro}</p>
      <AuditFilterForm classification={classification} initial={initial} onApply={apply} />
      {log.error ? <ErrorPanel problem={log.error.problem} /> : null}
      {log.data ? <AuditResults result={log.data} loading={log.loading} onPage={setPage} /> : null}
      {!log.data && !log.error ? (
        <p className="muted" aria-live="polite">
          Loading…
        </p>
      ) : null}
    </div>
  );
}

function AuditFilterForm({
  classification,
  initial,
  onApply,
}: {
  classification: string;
  initial: AuditFilters;
  onApply: (filters: AuditFilters) => void;
}) {
  const [draft, setDraft] = useState<AuditFilters>(initial);

  function set(name: keyof AuditFilters, value: string) {
    setDraft((current) => ({ ...current, [name]: value.trim() }));
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onApply(draft);
  }

  /** Back to the filters the screen opened with, so a VA Submission's own history stays that VA Submission's. */
  function clear() {
    setDraft(initial);
    onApply(initial);
  }

  return (
    <form className="filters" onSubmit={submit} aria-label="Filter the audit log">
      <ClassifiedField id="audit-actor" label="Person (user id)" classification={classification}>
        <input
          id="audit-actor"
          type="text"
          pattern={UUID_PATTERN}
          title="A user id, such as 123e4567-e89b-12d3-a456-426614174000"
          value={draft.actor}
          onChange={(event) => set("actor", event.target.value)}
        />
      </ClassifiedField>
      <ClassifiedField id="audit-action" label="Action" classification={classification}>
        <select id="audit-action" value={draft.action} onChange={(event) => set("action", event.target.value)}>
          <option value="">Any action</option>
          {AUDIT_ACTIONS.map((action) => (
            <option key={action} value={action}>
              {actionLabel(action)}
            </option>
          ))}
        </select>
      </ClassifiedField>
      <ClassifiedField id="audit-system" label="System (id)" classification={classification}>
        <input
          id="audit-system"
          type="text"
          pattern={UUID_PATTERN}
          title="A System id, such as 123e4567-e89b-12d3-a456-426614174000"
          value={draft.system}
          onChange={(event) => set("system", event.target.value)}
        />
      </ClassifiedField>
      <ClassifiedField id="audit-submission" label="VA Submission (id)" classification={classification}>
        <input
          id="audit-submission"
          type="text"
          pattern={UUID_PATTERN}
          title="A VA Submission id, such as 123e4567-e89b-12d3-a456-426614174000"
          value={draft.submission}
          onChange={(event) => set("submission", event.target.value)}
        />
      </ClassifiedField>
      <ClassifiedField id="audit-from" label="From (Singapore date)" classification={classification}>
        <input id="audit-from" type="date" value={draft.from} onChange={(event) => set("from", event.target.value)} />
      </ClassifiedField>
      <ClassifiedField id="audit-to" label="To (Singapore date)" classification={classification}>
        <input id="audit-to" type="date" value={draft.to} onChange={(event) => set("to", event.target.value)} />
      </ClassifiedField>
      <div className="filters__actions">
        <button type="submit" className="button">
          Apply
        </button>
        <button type="button" className="button button--secondary" onClick={clear}>
          Clear
        </button>
      </div>
    </form>
  );
}

function AuditResults({
  result,
  loading,
  onPage,
}: {
  result: AuditPage;
  loading: boolean;
  onPage: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(result.total / PAGE_SIZE));
  if (result.total === 0) return <p className="muted">No entries match.</p>;
  return (
    <div aria-busy={loading}>
      <div className="table-scroll">
        <table className="audit-table">
          <caption className="muted">
            {result.total} {result.total === 1 ? "entry" : "entries"}, page {result.page + 1} of {pages}
          </caption>
          <thead>
            <tr>
              <th scope="col">When (Singapore)</th>
              <th scope="col">Person</th>
              <th scope="col">Roles</th>
              <th scope="col">Action</th>
              <th scope="col">Outcome</th>
              <th scope="col">System</th>
              <th scope="col">VA Submission</th>
              <th scope="col">Finding</th>
              <th scope="col">Detail</th>
              <th scope="col">Correlation id</th>
            </tr>
          </thead>
          <tbody>
            {result.items.map((entry) => (
              <AuditRow key={entry.id} entry={entry} />
            ))}
          </tbody>
        </table>
      </div>
      <nav className="pager" aria-label="Audit log pages">
        <button
          type="button"
          className="button button--secondary"
          disabled={loading || result.page === 0}
          onClick={() => onPage(result.page - 1)}
        >
          Previous
        </button>
        <button
          type="button"
          className="button button--secondary"
          disabled={loading || result.page + 1 >= pages}
          onClick={() => onPage(result.page + 1)}
        >
          Next
        </button>
      </nav>
    </div>
  );
}

function AuditRow({ entry }: { entry: AuditEntry }) {
  return (
    <tr>
      <td>{when(entry.occurredAt)}</td>
      <td className="mono">{entry.actorId ?? "-"}</td>
      <td>{entry.actorRole ?? "-"}</td>
      <td>{actionLabel(entry.action)}</td>
      <td>{entry.outcome}</td>
      <td className="mono">{entry.systemId ?? "-"}</td>
      <td className="mono">{entry.submissionId ?? "-"}</td>
      <td className="mono">{entry.findingId ?? "-"}</td>
      <td>
        {Object.entries(entry.detail)
          .map(([key, value]) => `${key}: ${String(value)}`)
          .join(", ") || "-"}
      </td>
      <td className="mono">{entry.correlationId}</td>
    </tr>
  );
}
