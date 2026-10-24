# An endorsement is a step-up move, and a VA POC counts at the endorsing step only by acting

Status: accepted (item 2 provisional, awaiting a human decision)

Ticket 34 (stories 65, 67 and 70) lets a VA POC endorse a VA Submission Awaiting Endorsement. It adds one migration (V134), one endpoint, one screen and no module. The command is in `submission`, which owns the state machine and the segregation-of-duties guard (ADR 0035), and it builds the rule ADR 0039 item 5 recorded for how a VA POC counts.

1. **The command and its checks.** `POST /api/submissions/{id}/endorse` (role `VA_POC`, body `{}`) checks, in order:
   1. the role (`403` for anyone else);
   2. step-up (IM8 ac-2, story 77): `StepUp.require("submission-endorse")`, so without a sign-in from the last five minutes it is `403 step-up-required` and nothing is read or changed. It comes before the VA Submission is looked up, as the Register apply's does (ADR 0018), so a stale session learns nothing, not even whether the id exists;
   3. the access guard on `Need.ENDORSE` (every System for a VA POC; an id that does not exist is `404`);
   4. with the VA Submission's row held exclusively (`SubmissionStore.lockForMove`, as every move does), segregation of duties (`403 sod-conflict`) and then the state (`409 invalid-transition`, `reason=state-not-endorsable`).

   On success the caller is recorded at step `ENDORSE`, the VA Submission moves to Endorsed, and the move is audited and published (item 4).

2. **How a VA POC counts for segregation of duties** (builds ADR 0039 item 5, still provisional). `SubmissionStep` gains `ENDORSE`, whose need is `Need.ENDORSE` but which has no Register assignment (`SubmissionStep.assignedBy()` is empty for it). So:
   - a VA POC is refused the endorsing step when they acted at another step of that VA Submission (`submission_participation`: they submitted it, or reviewed, returned or passed it as its Cyber Manager), or when the Register names them as its System's SMO/Tech Lead or Cyber Manager, even before they act;
   - holding the VA POC role puts nobody at the endorsing step, so a VA POC the Register names as a System's SMO/Tech Lead or Cyber Manager still starts, submits and reviews it, and only acting at the endorsing step (endorsing today; a VA POC's return, ticket 35, and an Exemption decision, ticket 40, will record the same step) counts against them.

   The rule is still the one component and the one query (ADR 0035 item 1); only which steps are assigned changed. `SegregationOfDuties` keeps refusing a scope over every System as an assignment, as a programming error, so a later step that names a VA POC's need as its assignment fails every test that reaches it. The other VA POC endorses a VA Submission one VA POC is refused (story 70); there is no delegation.

   Open for a human: the glossary lists deciding an Exemption as a step of its own, while ADR 0039 item 5 puts it at the endorsing step. Built as ADR 0039 says (one VA POC step), so a VA POC who rejected an Exemption may still endorse the same VA Submission. V134 keeps `DECIDE` allowed in the step check, unused, for ticket 40 to use or drop.

3. **Endorsed Late and the milestones.** The move is the one statement every move out of In Review uses (`SubmissionStore.MOVED`), which now also sets `endorsed_at` (V133, the Cycle matrix's endorsed milestone) to the clock's instant and `endorsed_late` when the state moves to Endorsed. Endorsed Late is whether the window the old state, Awaiting Endorsement, waited on had passed on today's business date (Asia/Singapore), asked of V20's `window_passed`, so the Quarter's last day itself is on time and the day after is late; no due date is written out anywhere else. The flag uses `OVERDUE_AFTER_MOVE`, so the Overdue flag clears: Endorsed waits on no window, and Endorsed Late is the lasting mark of a late endorsement (reviewer decision of 2026-10-23, "Overdue clears when a submit moves into a window that has not passed").

4. **Audit and events.** An endorsement is `SUBMISSION_ENDORSED` with `state_from`, `state_to` and `endorsed_late`, a new detail key (a flag, never a date) that V134 adds to the allow-list. Refusals carry a fixed `reason`: `sod-conflict` or `state-not-endorsable`. A refusal for want of a step-up is the `request-refused` entry every step-up refusal writes, with the intent. Each endorsement publishes a `SubmissionEvent` (Awaiting Endorsement to Endorsed) after the commit, for the FYI notification (ticket 37).

5. **The read models.** The VA POC's Quarter board reads `Need.ENDORSE`, which now has a step, so its `sodConflict` marks the VA Submissions where segregation of duties refuses the caller the endorsement, by the same rule as the command (`Submissions.conflictedAt`). A person who is both a VA POC and a Cyber Manager therefore sees the conflicts of the endorsing step on their board (ADR 0037).

6. **The screen.** `/va-poc/submissions/{id}`, opened from a started System's card on the VA POC's Quarter board, shows the state with the Overdue and Endorsed Late flags and the Quarter's last day, the not-applicable justifications and every Finding (100 to a page, the most severe first) with what was proposed and its review, read only, and Endorse while it is Awaiting Endorsement. The read-only cells are the review screen's, moved to `screens/shared` (`FindingCells`, `LatestReview`) so both screens render a Finding one way. A `403 step-up-required` opens the app's step-up explanation (ADR 0004) and brings the VA POC back to the screen to press Endorse again; every other refusal is the server's problem, and after a `409` the screen reads the VA Submission again. The Cyber Manager's board cards now open the review screen the same way.

Not done here, on purpose:
- The VA POC's return from Awaiting Endorsement (ticket 35), Exemptions (ticket 40), the Cycle close and Not Endorsed (ticket 41), and the notifications themselves (tickets 36 and 37).
- Any Playwright scenario (ticket 53).

Consequences:
- A VA POC who is also a System's SMO/Tech Lead or Cyber Manager is marked on their own board and refused its endorsement; with both VA POCs conflicted, nobody in the app can endorse it until the Register changes.
- Every move now records its milestone in one statement, so a later move to Awaiting Endorsement or Endorsed (an Exemption rejected back to its previous state, ticket 40) must decide whether it is a new milestone.

Amends ADR 0035 item 1 (the endorsing step is added with no Register assignment) and ADR 0037 (the VA POC's board marks conflicts at the endorsing step).
