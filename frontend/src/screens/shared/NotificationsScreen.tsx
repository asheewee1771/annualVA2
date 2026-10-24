import { useCallback, useState } from "react";
import { dayLabel } from "../../api/cycles.ts";
import {
  NOTIFICATIONS_PAGE_SIZE,
  fetchNotifications,
  markNotificationRead,
  type AppNotification,
} from "../../api/notifications.ts";
import { ApiError } from "../../api/problem.ts";
import { navigate } from "../../app/router.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";
import { Pager } from "./Pager.tsx";
import { STATE_LABELS } from "./systemStates.ts";

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
  const [failure, setFailure] = useState<ApiError | undefined>(undefined);
  const load = useCallback(() => fetchNotifications(page), [page]);
  const list = useResource(load, refresh);

  async function markRead(item: AppNotification): Promise<boolean> {
    try {
      await markNotificationRead(item.id);
      setFailure(undefined);
      return true;
    } catch (error) {
      if (error instanceof ApiError) setFailure(error);
      return false;
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
      {failure ? <ErrorPanel problem={failure.problem} /> : null}
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
                  <NotificationRow key={item.id} item={item} onOpen={open} onRead={read} />
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
}: {
  item: AppNotification;
  onOpen: (item: AppNotification) => void;
  onRead: (item: AppNotification) => void;
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
        <button type="button" className="button" onClick={() => onOpen(item)}>
          Open
        </button>{" "}
        {item.readAt === null ? (
          <button type="button" className="button button--secondary" onClick={() => onRead(item)}>
            Mark read
          </button>
        ) : null}
      </td>
    </tr>
  );
}
