import type { ReactNode } from "react";

/**
 * A labelled progress bar with a summary under it. The summary is a polite live region, so a screen reader hears the new
 * counts after each change without the focus moving.
 */
export function ProgressBar({
  id,
  label,
  value,
  max,
  children,
}: {
  id: string;
  label: string;
  value: number;
  max: number;
  /** The counts in words. */
  children: ReactNode;
}) {
  return (
    <div className="progress">
      <label htmlFor={id}>{label}</label>
      <progress id={id} value={value} max={Math.max(max, 1)} />
      <p className="progress__summary" aria-live="polite">
        {children}
      </p>
    </div>
  );
}
