import { useEffect, useId, useRef, useState } from "react";

type Phase = "idle" | "confirming" | "returning";

/**
 * A Delete button that asks once before it deletes (ticket 60): the first press shows what deleting does and a second
 * button that does it, which takes the focus and is described by that sentence. Cancel, or a delete the server refused,
 * puts the first button back with the focus on it; after a delete that succeeded the caller moves the focus, since this
 * row is about to go. The caller sends the request, shows what the server answered and says whether it deleted; while
 * it is on its way both buttons wait. Sit it inside {@link Editable}, so it is disabled once the VA Submission is
 * submitted.
 */
export function ConfirmDelete({
  name,
  what,
  consequence,
  onDelete,
}: {
  /** What is deleted, as text: read out with the buttons. */
  name: string;
  /** What the button deletes, such as "file" or "Finding". */
  what: string;
  /** What deleting it does, in one or two sentences. */
  consequence: string;
  /** Sends the deletion; true when it was deleted. */
  onDelete: () => Promise<boolean>;
}) {
  const [phase, setPhase] = useState<Phase>("idle");
  const [busy, setBusy] = useState(false);
  const confirm = useRef<HTMLButtonElement>(null);
  const opener = useRef<HTMLButtonElement>(null);
  const describedBy = useId();

  useEffect(() => {
    if (phase === "confirming") confirm.current?.focus();
    if (phase === "returning") opener.current?.focus();
  }, [phase]);

  async function remove() {
    setBusy(true);
    const deleted = await onDelete();
    setBusy(false);
    if (!deleted) setPhase("returning");
  }

  if (phase !== "confirming") {
    return (
      <button ref={opener} type="button" className="button button--secondary" onClick={() => setPhase("confirming")}>
        Delete<span className="visually-hidden"> {name}</span>
      </button>
    );
  }
  return (
    <span>
      <span id={describedBy}>{consequence}</span>{" "}
      <button
        ref={confirm}
        type="button"
        className="button"
        aria-describedby={describedBy}
        disabled={busy}
        onClick={() => void remove()}
      >
        {busy ? "Deleting…" : `Delete ${what}`}
        <span className="visually-hidden"> {name}</span>
      </button>{" "}
      <button type="button" className="button button--secondary" disabled={busy} onClick={() => setPhase("returning")}>
        Cancel
      </button>
    </span>
  );
}
