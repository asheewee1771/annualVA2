import { AppLink } from "./AppLink.tsx";
import type { Area } from "./roles.ts";

/** One entry per role the person holds; the area they are in is marked for assistive technology. */
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
      </ul>
    </nav>
  );
}
