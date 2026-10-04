import { useState } from "react";
import type { Problem } from "../api/problem.ts";

/** Shows a problem's title and detail as text, with the traceId behind a toggle for support. */
export function ErrorPanel({ problem }: { problem: Problem }) {
  const [showReference, setShowReference] = useState(false);
  return (
    <div className="error-panel" role="alert">
      <strong>{problem.title}</strong>
      {problem.detail ? <p>{problem.detail}</p> : null}
      {problem.traceId ? (
        <p>
          <button type="button" className="button button--secondary" onClick={() => setShowReference((v) => !v)}>
            {showReference ? "Hide reference" : "Show reference"}
          </button>
          {showReference ? <span className="error-panel__reference"> {problem.traceId}</span> : null}
        </p>
      ) : null}
    </div>
  );
}
