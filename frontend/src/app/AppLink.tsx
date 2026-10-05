import type { MouseEvent, ReactNode } from "react";
import { navigate } from "./router.ts";

interface AppLinkProps {
  path: string;
  /** Set on the link to the page the person is on, for assistive technology. */
  current?: boolean;
  children: ReactNode;
}

/** A link inside the app: a plain click changes the route without a page load; open-in-new-tab and the like still work. */
export function AppLink({ path, current, children }: AppLinkProps) {
  function follow(event: MouseEvent<HTMLAnchorElement>) {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(path);
  }

  return (
    <a href={path} aria-current={current ? "page" : undefined} onClick={follow}>
      {children}
    </a>
  );
}
