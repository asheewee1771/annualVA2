# An endorsement is a step-up move, and a VA POC counts at the endorsing step only by acting

Status: accepted (item 2 decided 2026-10-25, docs/agents/reviewer-decisions.md)

Ticket 34 (stories 65, 67 and 70) lets a VA POC endorse a VA Submission Awaiting Endorsement. It adds one migration (V25), one endpoint, one screen and no module. The command is in `submission`, which owns the state machine and the segregation-of-duties guard (ADR 0035), and it builds the rule ADR 0039 item 5 recorded for how a VA POC counts.

1. **The command and its checks.** `POST /api/submissions/{id}/endorse` (role `VA_POC`, body `{}`) checks, in order:
   1. the role (`403` for anyone else);
   2. step-up (IM8 ac-2, story 77): `StepUp.require("submission-endorse")`, so without a sign-in from the last five minutes it is `403 step-up-required` and nothing is read or changed. It comes before the VA Submission is looked up, as the Register apply's does (ADR 0018), so a stale session learns nothing, not even whether the id exists;
   3. the access guard on `Need.ENDORSE` (every System for a VA POC; an id that does not exist is `404`);
   4. with the VA Submission's row held exclusively (`SubmissionStore.lockForMove`, as every move does), segregation of duties (`403 sod-conflict`) and then the state (`409 invalid-transition`, `reason=state-not-endorsable`).

   On success the caller is recorded at step `ENDORSE`, the VA Submission moves to Endorsed, and the move is audited and published (item 4).

   Checks 3 and 4 are the review commands' own (ADR 0035 item 2, ADR 0039 item 1), written once: `Submissions.atStep` takes a `Gate` (the step, whose need the access guard asks, the one state it acts from, and the fixed reason and sentence of a refusal in another state) and the hold. `REVIEWING` and `ENDORSING` are the two gates today; tickets 35 and 40 add theirs. `atStep` answers an `AtStep` that remembers the gate and the hold, and the one method that moves the state (`moved`) still refuses one held only shared (ADR 0039 item 1). `ReviewTarget` stays the review writes' type and no longer carries the hold.

2. **How a VA POC counts for segregation of duties** (builds ADR 0039 item 5; decided 2026-10-25). `SubmissionStep` gains `ENDORSE`, whose need is `Need.ENDORSE` but which has no Register assignment (`SubmissionStep.assignedBy()` is empty for it). So:
   - a VA POC is refused the endorsing step when they acted at another step of that VA Submission (`submission_participation`: they submitted it, or reviewed, returned or passed it as its Cyber Manager), or when the Register names them as its System's SMO/Tech Lead or Cyber Manager, even before they act;
   - holding the VA POC role puts nobody at the endorsing step, so a VA POC the Register names as a System's SMO/Tech Lead or Cyber Manager still starts, submits and reviews it, and only acting at the endorsing step (endorsing today; a VA POC's return, ticket 35, and an Exemption decision, ticket 40, will record the same step) counts against them.

   The rule is still the one component and the one query (ADR 0035 item 1); only which steps are assigned changed. `SegregationOfDuties` keeps refusing a scope over every System as an assignment, as a programming error, so a later step that names a VA POC's need as its assignment fails every test that reaches it. The other VA POC endorses a VA Submission one VA POC is refused (story 70); there is no delegation.

   Decided 2026-10-25 (docs/agents/reviewer-decisions.md), one VA POC step: the glossary lists deciding an Exemption as a step of its own, while ADR 0039 item 5 puts it at the endorsing step. Built as ADR 0039 says (one VA POC step), so a VA POC who rejected an Exemption may still endorse the same VA Submission. V25 keeps `DECIDE` allowed in the step check, unused, for ticket 40 to use or drop.

3. **Endorsed Late and the milestones.** Each move has its own statement, setting only its own columns beside the state and `OVERDUE_AFTER_MOVE`: a return (`MOVED`), a pass to endorsement (`PASSED`, `passed_to_endorsement_at`) and an endorsement (`ENDORSED`, which sets `endorsed_at`, V24's endorsed milestone, to the clock's instant and `endorsed_late`, and returns `endorsed_late` for the audit entry). The command runs its statement through `moved`, which records the entry and publishes the event, so the shared path never branches on the target state. Endorsed Late is whether the window the old state, Awaiting Endorsement, waited on had passed on today's business date (Asia/Singapore), asked of V20's `window_passed`, so the Quarter's last day itself is on time and the day after is late; no due date is written out anywhere else. The flag uses `OVERDUE_AFTER_MOVE`, so the Overdue flag clears: Endorsed waits on no window, and Endorsed Late is the lasting mark of a late endorsement (reviewer decision of 2026-10-23, "Overdue clears when a submit moves into a window that has not passed").

4. **Audit and events.** An endorsement is `SUBMISSION_ENDORSED` with `state_from`, `state_to` and `endorsed_late`, a new detail key (a flag, never a date) that V25 adds to the allow-list. Refusals carry a fixed `reason`: `sod-conflict` or `state-not-endorsable`. A refusal for want of a step-up is the `request-refused` entry every step-up refusal writes, with the intent. Each endorsement publishes a `SubmissionEvent` (Awaiting Endorsement to Endorsed) after the commit, for the FYI notification (ticket 37).

5. **The read models.** The VA POC's Quarter board reads `Need.ENDORSE`, which now has a step, so its `sodConflict` marks the VA Submissions where segregation of duties refuses the caller the endorsement, by the same rule as the command (`Submissions.conflictedAt`). A person who is both a VA POC and a Cyber Manager therefore sees the conflicts of the endorsing step on their board (ADR 0037).

6. **The screen.** `/va-poc/submissions/{id}`, opened from a started System's card on the VA POC's Quarter board, shows the state with the Overdue and Endorsed Late flags and the Quarter's last day, the not-applicable justifications and every Finding (100 to a page, the most severe first) with what was proposed and its review, read only, and Endorse while it is Awaiting Endorsement. The Findings list (`FindingsList`, with the reader's review cell), the not-applicable section (`NotApplicableSection`, with the reader's controls) and their cells are the review screen's, moved to `screens/shared`, so both screens render them one way. A refusal stays on the screen after the panel goes, and Endorse stays disabled until the new state is read. A `403 step-up-required` opens the app's step-up explanation (ADR 0004) and brings the VA POC back to the screen to press Endorse again; every other refusal is the server's problem, and after a `409` the screen reads the VA Submission again. The Cyber Manager's board cards now open the review screen the same way.

Not done here, on purpose:
- The VA POC's return from Awaiting Endorsement (ticket 35), Exemptions (ticket 40), the Cycle close and Not Endorsed (ticket 41), and the notifications themselves (tickets 36 and 37).
- Any Playwright scenario (ticket 53).

Consequences:
- A VA POC who is also a System's SMO/Tech Lead or Cyber Manager is marked on their own board and refused its endorsement; with both VA POCs conflicted, nobody in the app can endorse it until the Register changes.
- A later move back to Awaiting Endorsement or Endorsed (an Exemption rejected back to its previous state, ticket 40) chooses its own statement, and so whether it is a new milestone.

Amends ADR 0035 items 1 and 2 (the endorsing step is added with no Register assignment; the step checks are one gate), ADR 0037 (the VA POC's board marks conflicts at the endorsing step) and ADR 0039 item 1 (`atStep` replaces `atReviewStep`, and the hold moves from `ReviewTarget` to `AtStep`).
