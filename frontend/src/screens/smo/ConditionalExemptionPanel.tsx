import { useState, type FormEvent } from "react";
import { dayLabel } from "../../api/cycles.ts";
import { MAX_EVIDENCE_LENGTH, recordVaHappened } from "../../api/exemptions.ts";
import { ApiError, type Problem } from "../../api/problem.ts";
import type { ConditionalExemption } from "../../api/submissions.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { LapsedFlag } from "../shared/LapsedFlag.tsx";

const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

/** The VA happened form: the day and what shows it. Every check is the server's; its problem names the wrong field. */
function VaHappenedForm({
  exemption,
  classification,
  onRecorded,
}: {
  exemption: ConditionalExemption;
  classification: string;
  onRecorded: () => void;
}) {
  const [vaDate, setVaDate] = useState("");
  const [evidence, setEvidence] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(undefined);
    try {
      await recordVaHappened(exemption.id, { vaDate, evidence });
      onRecorded();
    } catch (failure) {
      const refused = failure instanceof ApiError ? failure.problem : UNREACHABLE;
      setProblem(refused);
      setBusy(false);
      if (refused.status === 409) onRecorded();
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} aria-describedby={problem ? "va-happened-problem" : undefined}>
      <ClassifiedField label="Date the VA happened" classification={classification} id="va-happened-date">
        <input
          id="va-happened-date"
          type="date"
          required
          value={vaDate}
          disabled={busy}
          aria-invalid={problem?.field === "vaDate"}
          onChange={(event) => setVaDate(event.target.value)}
        />
      </ClassifiedField>
      <ClassifiedField
        label="Evidence: who did the VA and its report reference"
        classification={classification}
        id="va-happened-evidence"
      >
        <textarea
          id="va-happened-evidence"
          rows={3}
          required
          maxLength={MAX_EVIDENCE_LENGTH}
          value={evidence}
          disabled={busy}
          aria-invalid={problem?.field === "evidence"}
          onChange={(event) => setEvidence(event.target.value)}
        />
      </ClassifiedField>
      {problem ? (
        <div id="va-happened-problem">
          <ErrorPanel problem={problem} />
        </div>
      ) : null}
      <p>
        <button type="submit" className="button" disabled={busy}>
          {busy ? "Recording…" : "Record that the VA happened"}
        </button>
      </p>
    </form>
  );
}

/**
 * Where a conditional Exemption stands for its SMO/Tech Lead (story 47): until its scheduled VA is recorded, the VA
 * happened form and the day it lapses by; once recorded, waiting on a VA POC's confirmation; then confirmed. A lapsed one
 * says so: the VA Submission is back where it was before the request, with new windows, or Not Endorsed.
 */
export function ConditionalExemptionPanel({
  exemption,
  classification,
  onRecorded,
}: {
  exemption: ConditionalExemption;
  classification: string;
  onRecorded: () => void;
}) {
  if (exemption.lapsedAt) {
    return (
      <div className="notice" role="status">
        <p>
          <LapsedFlag lapsed /> The scheduled VA was not recorded by {dayLabel(exemption.lapsesOn)}, so the conditional
          Exemption lapsed. The VA Submission is back where it was before the request, with the new windows above, or
          Not Endorsed if its VA Cycle had closed.
        </p>
      </div>
    );
  }
  if (exemption.confirmedAt) {
    return (
      <div className="notice" role="status">
        <p>A VA POC confirmed the VA: the Exemption is no longer conditional.</p>
      </div>
    );
  }
  if (exemption.vaHappenedOn) {
    return (
      <div className="notice" role="status">
        <p>
          Conditional: you recorded that the VA happened on {dayLabel(exemption.vaHappenedOn)}. It is waiting on a VA
          POC&apos;s confirmation and no longer lapses.
        </p>
      </div>
    );
  }
  return (
    <section className="card" aria-labelledby="va-happened">
      <h3 id="va-happened">Record the scheduled VA</h3>
      <p>
        Conditional: this Exemption rests on a VA scheduled for {dayLabel(exemption.vaDate)}. Record that it happened by{" "}
        {dayLabel(exemption.lapsesOn)}, or the Exemption lapses and the VA Submission goes back where it was before the
        request, with two weeks to submit.
      </p>
      <VaHappenedForm exemption={exemption} classification={classification} onRecorded={onRecorded} />
    </section>
  );
}
