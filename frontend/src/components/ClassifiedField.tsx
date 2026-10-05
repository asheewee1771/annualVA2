import type { ReactNode } from "react";

interface ClassifiedFieldProps {
  /** The visible label of the field. */
  label: string;
  /** IM8 dp-8: the classification shown next to the field, the same text as the banner. */
  classification: string;
  /** The input itself, which must carry the id given here. */
  id: string;
  children: ReactNode;
}

/** A labelled input with its classification label beside it (IM8 dp-8). */
export function ClassifiedField({ label, classification, id, children }: ClassifiedFieldProps) {
  return (
    <div className="field">
      <label htmlFor={id}>
        {label} <span className="field__classification muted">({classification})</span>
      </label>
      {children}
    </div>
  );
}
