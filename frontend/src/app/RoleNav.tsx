import type { MouseEvent } from "react";
import type { Area } from "./roles.ts";
import { navigate } from "./router.ts";

/** One entry per role the person holds; the current area is marked for assistive technology. */
export function RoleNav({ areas, currentPath }: { areas: Area[]; currentPath: string }) {
  function follow(event: MouseEvent<HTMLAnchorElement>, path: string) {
    // Let the browser handle open-in-new-tab and the like.
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(path);
  }

  return (
    <nav className="role-nav" aria-label="Your roles">
      <ul>
        {areas.map((area) => (
          <li key={area.role}>
            <a
              href={area.path}
              aria-current={area.path === currentPath ? "page" : undefined}
              onClick={(event) => follow(event, area.path)}
            >
              {area.label}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
