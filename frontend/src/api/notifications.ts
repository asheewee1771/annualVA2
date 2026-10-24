import { request } from "./client.ts";
import type { SystemState } from "./dashboards.ts";

/**
 * A notification as the server lists it (GET /api/notifications): the System, its state, what to do, the due date and a
 * link, never a Finding (story 91). The message is the server's fixed sentence; the System's ID and name and the
 * handler's name are rendered as text only.
 */
export interface AppNotification {
  id: string;
  systemId: string;
  systemCode: string;
  systemName: string;
  submissionId: string | null;
  state: SystemState;
  action: string;
  message: string;
  /** A business date (Asia/Singapore), or null when nothing is due. */
  dueOn: string | null;
  /** An app path, opened in the app. */
  link: string;
  createdAt: string;
  readAt: string | null;
  /** For an item both VA POCs received: who handled it, once one did. */
  handledBy: string | null;
  handledAt: string | null;
}

export interface NotificationPage {
  items: AppNotification[];
  page: number;
  size: number;
  total: number;
}

export const NOTIFICATIONS_PAGE_SIZE = 50;

export function fetchNotifications(page: number): Promise<NotificationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(NOTIFICATIONS_PAGE_SIZE) });
  return request<NotificationPage>("GET", `/api/notifications?${query.toString()}`);
}

export function markNotificationRead(id: string): Promise<AppNotification> {
  return request<AppNotification>("POST", `/api/notifications/${encodeURIComponent(id)}/read`, {});
}
