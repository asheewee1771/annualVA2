import { useSyncExternalStore } from "react";

/**
 * The smallest router that does the job (ADR 0002): the path is the route, kept in the History API.
 * `navigate` changes it and tells every `usePath` subscriber; the back button is handled by popstate.
 */
const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  window.addEventListener("popstate", listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("popstate", listener);
  };
}

export function usePath(): string {
  return useSyncExternalStore(subscribe, () => window.location.pathname);
}

/** Go to a path. `replace` swaps the current history entry, for redirects that must not trap the back button. */
export function navigate(path: string, replace = false): void {
  if (path === window.location.pathname) return;
  if (replace) {
    window.history.replaceState(null, "", path);
  } else {
    window.history.pushState(null, "", path);
  }
  listeners.forEach((listener) => listener());
}
