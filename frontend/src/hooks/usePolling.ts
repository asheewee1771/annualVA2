import { useEffect, useState } from "react";

/** How long between asking again, and how long before the person is told it is taking longer than usual. */
const INTERVAL_MS = 3000;
const SLOW_AFTER_MS = 2 * 60 * 1000;

/**
 * Asks again every few seconds while something is on its way (`active`), after each answer has arrived (`busy` is false),
 * and not while the tab is hidden: it asks at once when the person comes back. A failed answer does not stop it, because it
 * waits on `busy` and not on the data. `slow` turns true once it has been waiting for two minutes, so the screen can say
 * that it is taking longer than usual.
 */
export function usePolling(active: boolean, busy: boolean, reload: () => void): { slow: boolean } {
  const [hidden, setHidden] = useState(document.hidden);
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    const onChange = () => setHidden(document.hidden);
    document.addEventListener("visibilitychange", onChange);
    return () => document.removeEventListener("visibilitychange", onChange);
  }, []);

  useEffect(() => {
    if (!active || busy || hidden) return;
    const timer = window.setTimeout(reload, INTERVAL_MS);
    return () => window.clearTimeout(timer);
  }, [active, busy, hidden, reload]);

  useEffect(() => {
    if (!active) {
      setSlow(false);
      return;
    }
    const timer = window.setTimeout(() => setSlow(true), SLOW_AFTER_MS);
    return () => window.clearTimeout(timer);
  }, [active]);

  return { slow };
}
