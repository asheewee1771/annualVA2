import { useRef, useState } from "react";
import { closeCycle, dayLabel, type ClosedCycle } from "../../api/cycles.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import { useCycleChoice } from "../../app/CycleChoice.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/**
 * Closes a VA Cycle early (stories 17 and 68), for a VA POC, once its Q4 has ended: a confirm dialog says what the close
 * does, and the close needs a sign-in from the last five minutes (without one the app explains the fresh sign-in, and
 * the VA POC presses it again on return). The server decides every refusal; the panel shows its problem.
 *
 * @param closesOn the day the Cycle would close by itself
 * @param onClosed called with what the close answered, once it is recorded
 */
function CloseCyclePanel({
  year,
  closesOn,
  onClosed,
}: {
  year: number;
  closesOn: string;
  onClosed: (closed: ClosedCycle) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function close() {
    dialog.current?.close();
    setBusy(true);
    setProblem(undefined);
    try {
      onClosed(await closeCycle(year));
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.problem : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card" aria-labelledby="close-cycle-title">
      <h3 id="close-cycle-title">Close VA Cycle {year}</h3>
      <p className="muted">
        Q4 has ended. VA Cycle {year} closes by itself at the end of {dayLabel(closesOn)}, which leaves time for late Q4
        endorsements. Close it earlier only when nothing more will be endorsed: every VA Submission that is not Endorsed
        or Exempted, and every System without one, becomes Not Endorsed, and no VA Submission of the Cycle takes another
        step. You need to have signed in within the last five minutes.
      </p>
      {problem ? <ErrorPanel problem={problem} /> : null}
      <p>
        <button type="button" className="button" disabled={busy} onClick={() => dialog.current?.showModal()}>
          Close VA Cycle {year}…
        </button>
      </p>
      <dialog ref={dialog} className="interstitial" aria-labelledby="close-cycle-confirm">
        <h2 id="close-cycle-confirm">Close VA Cycle {year} now?</h2>
        <p>
          Everything in VA Cycle {year} that is not Endorsed or Exempted becomes Not Endorsed. This cannot be undone.
        </p>
        <div className="interstitial__actions">
          <button type="button" className="button" onClick={() => void close()}>
            Close VA Cycle {year}
          </button>
          <button type="button" className="button button--secondary" onClick={() => dialog.current?.close()}>
            Cancel
          </button>
        </div>
      </dialog>
    </section>
  );
}

/**
 * Where the Cycle matrix offers the close: while the shown Cycle's Q4 has ended and it is not closed yet, by the shell's
 * list of Cycles. After a close it says what the close did and reads the list and the matrix again.
 *
 * @param onClosed called once the close is recorded, so the screen reads its own data again
 */
export function CloseCycleSection({ year, onClosed }: { year: number; onClosed: () => void }) {
  const choice = useCycleChoice();
  const [closed, setClosed] = useState<ClosedCycle | undefined>(undefined);
  const cycle = choice?.cycles.data?.cycles.find((candidate) => candidate.year === year);

  function recorded(result: ClosedCycle) {
    setClosed(result);
    choice?.cycles.reload();
    onClosed();
  }

  // A closed Cycle is no longer open, so the shell may move the matrix to the Cycle running today: say what was closed.
  if (closed) {
    return (
      <p role="status">
        VA Cycle {closed.year} is closed: {closed.notEndorsed} VA Submission{closed.notEndorsed === 1 ? " is" : "s are"}{" "}
        now Not Endorsed.
      </p>
    );
  }
  if (cycle?.phase !== "CLOSING") return null;
  return <CloseCyclePanel year={cycle.year} closesOn={cycle.closesOn} onClosed={recorded} />;
}
