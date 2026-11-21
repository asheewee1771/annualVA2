import { createContext, useContext, type ReactNode } from "react";

/**
 * Whether the SMO/Tech Lead may change the VA Submission on screen. The prepare screen decides it once, from the state the
 * server gives (Draft or Returned for Rework); every control that changes the VA Submission reads it through
 * {@link Editable}.
 */
export const EditableContext = createContext(true);

/**
 * Holds controls that change the VA Submission. Once it is submitted they are shown disabled (a disabled fieldset), so
 * read-only is decided in one place and works one way; they then read as unavailable, not busy (`.editable:disabled`,
 * ticket 65). Downloads and the controls that only change what is shown sit outside. The server refuses every change
 * after submit in any case.
 */
export function Editable({ children }: { children: ReactNode }) {
  const editable = useContext(EditableContext);
  return (
    <fieldset className="plain-fieldset editable" disabled={!editable}>
      {children}
    </fieldset>
  );
}
