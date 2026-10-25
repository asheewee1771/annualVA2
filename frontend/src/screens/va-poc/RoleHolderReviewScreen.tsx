import { useState } from "react";
import { ApiError, type Problem } from "../../api/problem.ts";
import {
  completeRoleHolderReview,
  fetchRoleHolderReview,
  restoreRole,
  suspendRole,
  type DueReason,
  type HolderRole,
  type HolderState,
  type ReturnTrigger,
  type RoleHolder,
} from "../../api/roleHolders.ts";
import { isStepUpRequired } from "../../api/stepUp.ts";
import { ErrorPanel } from "../../components/ErrorPanel.tsx";
import { useResource } from "../../hooks/useResource.ts";

/** Times as the rest of the app shows them, in Singapore time. */
const WHEN = new Intl.DateTimeFormat("en-SG", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Singapore" });
const UNREACHABLE: Problem = { type: "about:blank", title: "The server could not be reached", status: 0 };

const ROLE_LABELS: Readonly<Record<HolderRole, string>> = {
  VA_POC: "VA POC",
  CYBER_MANAGER: "Cyber Manager",
  SMO: "SMO/Tech Lead",
  AUDITOR: "Auditor",
};

const STATE_LABELS: Readonly<Record<HolderState, string>> = {
  ACTIVE: "Active",
  LAPSED: "Lapsed (unused for 90 days)",
  SUSPENDED: "Suspended",
  EXPIRED: "Expired",
};

const TRIGGER_LABELS: Readonly<Record<ReturnTrigger, string>> = {
  REGISTER: "A Register upload named them again",
  QUARTER_START: "Their System's Quarter began",
  RETURN_FOR_REWORK: "Work was returned to them for rework",
  SUBMITTED_FOR_REVIEW: "Work was submitted to them for review",
  EXEMPTION_LAPSE: "A conditional Exemption lapsed back to them",
  CONFIGURATION: "The deployment configuration returned it",
};

const DUE_LABELS: Readonly<Record<DueReason, string>> = {
  "never-reviewed": "No review has been recorded yet.",
  "three-months": "The last review was 3 months ago or more.",
  "register-applied": "The System Register has been applied since the last review.",
};

function problemOf(failure: unknown): Problem {
  return failure instanceof ApiError ? failure.problem : UNREACHABLE;
}

function when(instant: string | null): string {
  return instant === null ? "" : WHEN.format(new Date(instant));
}

function person(holder: RoleHolder): string {
  return holder.displayName === null ? holder.email : `${holder.displayName} (${holder.email})`;
}

function system(holder: RoleHolder): string {
  return holder.systemCode === null ? "All Systems" : `${holder.systemCode} ${holder.systemName ?? ""}`.trim();
}

/** The flags beside a holder's state: why it is suspended, and anything that differs from the Register. */
function Flags({ holder }: { holder: RoleHolder }) {
  return (
    <>
      {holder.suspendedBecause === "IDENTITY_PROVIDER" ? (
        <span className="flag flag--danger">Disabled at the single sign-on</span>
      ) : null}
      {holder.suspendedBecause === "REVIEW" ? <span className="flag flag--warning">Suspended in a review</span> : null}
      {holder.disabledAtIdentityProvider && holder.suspendedBecause !== "IDENTITY_PROVIDER" ? (
        <span className="flag flag--danger">Disabled at the single sign-on</span>
      ) : null}
      {holder.namedInRegister === false ? <span className="flag flag--warning">Not named in the Register</span> : null}
      {holder.signedIn ? null : <span className="flag">Not signed in yet</span>}
    </>
  );
}

/** Suspend or Restore for one holder: a step-up action, never for the VA POC's own grant. */
function HolderAction({ holder, onChanged }: { holder: RoleHolder; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);
  const suspendable = (holder.role === "SMO" || holder.role === "CYBER_MANAGER") && holder.state !== "SUSPENDED";
  const restorable = holder.state === "SUSPENDED";

  async function act(change: (grantId: string) => Promise<RoleHolder>) {
    setBusy(true);
    setProblem(undefined);
    try {
      await change(holder.grantId);
      onChanged();
    } catch (failure) {
      const refused = problemOf(failure);
      // The app already explains a step-up and brings the VA POC back here, to press the button again.
      if (!isStepUpRequired(refused)) setProblem(refused);
    } finally {
      setBusy(false);
    }
  }

  if (holder.own) return <small className="muted">Your own role: the other VA POC acts on it.</small>;
  return (
    <>
      {suspendable ? (
        <button
          type="button"
          className="button button--secondary"
          disabled={busy}
          onClick={() => void act(suspendRole)}
        >
          Suspend
        </button>
      ) : null}
      {restorable ? (
        <button
          type="button"
          className="button button--secondary"
          disabled={busy}
          onClick={() => void act(restoreRole)}
        >
          Restore
        </button>
      ) : null}
      {problem ? <ErrorPanel problem={problem} /> : null}
    </>
  );
}

function HoldersTable({ holders, onChanged }: { holders: RoleHolder[]; onChanged: () => void }) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">Every role holder, the VA POCs first, then by System</caption>
        <thead>
          <tr>
            <th scope="col">System</th>
            <th scope="col">Role</th>
            <th scope="col">Person</th>
            <th scope="col">State</th>
            <th scope="col">Last used</th>
            <th scope="col">Change</th>
          </tr>
        </thead>
        <tbody>
          {holders.map((holder) => (
            <tr key={holder.grantId}>
              <td>{system(holder)}</td>
              <td>{ROLE_LABELS[holder.role]}</td>
              <td>{person(holder)}</td>
              <td>
                {STATE_LABELS[holder.state]} <Flags holder={holder} />
              </td>
              <td>{when(holder.lastUsedAt)}</td>
              <td>
                <HolderAction holder={holder} onChanged={onChanged} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ReturnsTable({ returns }: { returns: RoleHolder[] }) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">The roles that came back by themselves since the last review</caption>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Why</th>
            <th scope="col">System</th>
            <th scope="col">Role</th>
            <th scope="col">Person</th>
          </tr>
        </thead>
        <tbody>
          {returns.map((holder) => (
            <tr key={holder.grantId}>
              <td>{when(holder.returnedAt)}</td>
              <td>{holder.returnTrigger === null ? "" : TRIGGER_LABELS[holder.returnTrigger]}</td>
              <td>{system(holder)}</td>
              <td>{ROLE_LABELS[holder.role]}</td>
              <td>{person(holder)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Review completed: records that the caller has checked every holder listed now. */
function CompleteReview({ onCompleted }: { onCompleted: () => void }) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | undefined>(undefined);

  async function complete() {
    setBusy(true);
    setProblem(undefined);
    try {
      await completeRoleHolderReview();
      onCompleted();
    } catch (failure) {
      setProblem(problemOf(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <p>
      {problem ? <ErrorPanel problem={problem} /> : null}
      <button type="button" className="button" disabled={busy} onClick={() => void complete()}>
        Review completed
      </button>{" "}
      <small className="muted">Press it once you have checked every role holder listed above.</small>
    </p>
  );
}

/**
 * The VA POC's role-holder review (IM8 ac-4, story 80a): every role holder against the System Register, at each Register
 * upload and at least every 3 months. It shows the roles that came back by themselves since the last review, flags when a
 * review is due, and offers the review's one change, Suspend (until a Register upload names someone else), and Restore;
 * both need a fresh sign-in. The server decides what is due and refuses a change to the VA POC's own role.
 */
export function RoleHolderReviewScreen() {
  const review = useResource(fetchRoleHolderReview);
  const data = review.data;

  return (
    <div className="card">
      <h2>Role-holder review</h2>
      <p className="muted">
        Check every role holder against the System Register at each Register upload and at least every 3 months. A
        suspended role stops working at once, for that System only, until you restore it or a Register upload names
        someone else.
      </p>
      {review.error ? <ErrorPanel problem={review.error.problem} /> : null}
      {data ? (
        <>
          {data.reviewDue ? (
            <div className="notice" role="status">
              <p>
                <strong>A review is due.</strong> {data.dueBecause.map((reason) => DUE_LABELS[reason]).join(" ")}
              </p>
            </div>
          ) : null}
          {data.lastReview === null ? null : (
            <p className="muted">Last review recorded {when(data.lastReview.reviewedAt)}.</p>
          )}
          <section aria-labelledby="returns-title">
            <h3 id="returns-title">Came back by themselves since the last review</h3>
            {data.automaticReturns.length === 0 ? (
              <p className="muted">None.</p>
            ) : (
              <ReturnsTable returns={data.automaticReturns} />
            )}
          </section>
          <section aria-labelledby="holders-title">
            <h3 id="holders-title">Role holders ({data.holders.length})</h3>
            <HoldersTable holders={data.holders} onChanged={review.reload} />
          </section>
          <CompleteReview onCompleted={review.reload} />
        </>
      ) : null}
    </div>
  );
}
