import { AppLink } from "./AppLink.tsx";
import type { Area } from "./roles.ts";

/** Where every signed-in person with a role reads their notifications (story 92), whatever their role. */
export const NOTIFICATIONS_PATH = "/notifications";

/**
 * One entry per role the person holds, then their notifications; the page they are on is marked for assistive
 * technology.
 */
export function RoleNav({ areas, currentPath }: { areas: Area[]; currentPath: string | undefined }) {
  return (
    <nav className="role-nav" aria-label="Your roles">
      <ul>
        {areas.map((area) => (
          <li key={area.role}>
            <AppLink path={area.path} current={area.path === currentPath}>
              {area.label}
            </AppLink>
          </li>
        ))}
        <li>
          <AppLink path={NOTIFICATIONS_PATH} current={currentPath === NOTIFICATIONS_PATH}>
            Notifications
          </AppLink>
        </li>
      </ul>
    </nav>
  );
}
