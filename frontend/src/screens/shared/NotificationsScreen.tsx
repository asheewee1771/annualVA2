import { useCallback, useState } from "react";
import { dayLabel } from "../../api/cycles.ts";
import {
  NOTIFICATIONS_PAGE_SIZE,
  fetchNotifications,
  markNotificationRead,
  type AppNotification,
} from "../../api/notifications.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { navigate } from "../../app/router.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { Pager } from "./Pager.tsx";
import { STATE_LABELS } from "./systemStates.ts";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/** When a notification came, in Singapore time whatever the browser's own zone is. */
const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });

function when(instant: string): string {
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? instant : WHEN.format(date);
}

/**
 * Every notification the signed-in person has had, newest first (story 92), for every role. Each says only which
 * System, its state, what to do, by when, and opens the screen to do it in; an item both VA POCs received shows who
 * handled it once one did (story 69). Opening an item, or marking it read, marks it read on the server.
 */
export function NotificationsScreen() {
  const [page, setPage] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [failure, setFailure] = useState<Problem | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => fetchNotifications(page), [page]);
  const list = useResource(load, refresh);

  /** Marks one read; while that is under way its row's buttons are disabled, so a double click writes once. */
  async function markRead(item: AppNotification): Promise<boolean> {
    setBusy(true);
    try {
      await markNotificationRead(item.id);
      setFailure(undefined);
      return true;
    } catch (error) {
      setFailure(error instanceof ApiError ? error.problem : UNREACHABLE);
      return false;
    } finally {
      setBusy(false);
    }
  }

  /** Opens the item inside the app only: a link is always an app path, and anything else is not followed. */
  async function open(item: AppNotification) {
    const inApp = item.link.startsWith("/") && !item.link.startsWith("//");
    if (inApp && (item.readAt !== null || (await markRead(item)))) navigate(item.link);
  }

  async function read(item: AppNotification) {
    if (await markRead(item)) setRefresh((n) => n + 1);
  }

  const data = list.data;
  return (
    <div className="card">
      <h2>Notifications</h2>
      <p className="muted">
        What needs you, and what changed on your Systems. A notification names the System, its state, what to do and by
        when; open it to act.
      </p>
      {failure ? <ErrorPanel problem={failure} /> : null}
      {list.error ? <ErrorPanel problem={list.error.problem} /> : null}
      {data && data.total === 0 ? <p className="muted">You have no notifications.</p> : null}
      {data && data.total > 0 ? (
        <div aria-busy={list.loading}>
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th scope="col">When (Singapore)</th>
                  <th scope="col">System</th>
                  <th scope="col">State</th>
                  <th scope="col">What to do</th>
                  <th scope="col">Due</th>
                  <th scope="col">Status</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((item) => (
                  <NotificationRow key={item.id} item={item} busy={busy} onOpen={open} onRead={read} />
                ))}
              </tbody>
            </table>
          </div>
          <Pager
            page={data.page}
            pages={Math.max(1, Math.ceil(data.total / NOTIFICATIONS_PAGE_SIZE))}
            total={data.total}
            onPage={setPage}
            noun="notifications"
          />
        </div>
      ) : null}
      {!data && !list.error ? (
        <p className="muted" aria-live="polite">
          Loading…
        </p>
      ) : null}
    </div>
  );
}

function status(item: AppNotification): string {
  if (item.handledBy !== null) return `Handled by ${item.handledBy}`;
  return item.readAt === null ? "Unread" : "Read";
}

function NotificationRow({
  item,
  onOpen,
  onRead,
  busy,
}: {
  item: AppNotification;
  onOpen: (item: AppNotification) => void;
  onRead: (item: AppNotification) => void;
  /** A mark-read is under way: no second write starts until it settles. */
  busy: boolean;
}) {
  return (
    <tr className={item.readAt === null ? "notification--unread" : undefined}>
      <td>{when(item.createdAt)}</td>
      <td>
        <span className="mono">{item.systemCode}</span> {item.systemName}
      </td>
      <td>{STATE_LABELS[item.state] ?? item.state}</td>
      <td>{item.message}</td>
      <td>{item.dueOn ? dayLabel(item.dueOn) : "-"}</td>
      <td>{status(item)}</td>
      <td>
        <button type="button" className="button" disabled={busy} onClick={() => onOpen(item)}>
          Open
        </button>{" "}
        {item.readAt === null ? (
          <button type="button" className="button button--secondary" disabled={busy} onClick={() => onRead(item)}>
            Mark read
          </button>
        ) : null}
      </td>
    </tr>
  );
}
