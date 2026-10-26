# A review is an append-only row of what was read, and one segregation-of-duties rule guards every step

Status: accepted

Ticket 28 (stories 50 and 58, story 52 in part) lets the Cyber Manager assigned to a System confirm each proposed Finding Status of its VA Submission In Review, or mark it Disputed with a comment, and do the same for the justification of a Scan Report marked not applicable (ADR 0030 item 3). It also builds the segregation-of-duties guard the spec puts in the Submission Workflow. It adds one migration (V21), two endpoints, one screen and no module: the reviews are in `finding`, which keeps the Findings and the Scan Reports, and the guard is in `submission`, which keeps the steps.

1. **One segregation-of-duties rule.** A person may not act at more than one step of the same VA Submission. The rule lives in one component, `SegregationOfDuties` (in `submission`), with one entry point, `require(step, systemId, submissionId, refusedAs)`, and one query (`SubmissionStore.CONFLICTED`). A person is refused a step when either is true:
   - they acted at another step of that VA Submission (`submission_participation`);
   - the Register assigns them to another step of its System, even before they act. The System's SMO/Tech Lead counts as its submitter and its Cyber Manager as its reviewer. Each `SubmissionStep` names the `Need` that assigns it (`SUBMIT` to `Need.SUBMIT`, `REVIEW` to `Need.REVIEW`), and the component reads the caller's scope for every other step. A scope over every System (a VA POC's) has no place in the rule yet: meeting one is an `IllegalStateException`, not a silent "assigned to nothing", until the ticket that adds the endorsing step decides how a VA POC counts.

   It runs on:
   - **start**, on the VA Submission the start has just created or taken (a Not Started one a rejected Exemption request left), in the one place every step command records its step (`Submissions.stepped`, review fixes 2026-10-25): an SMO/Tech Lead who is also the System's Cyber Manager is refused. It runs after the Cycle and Quarter checks, so a start into a closed Cycle, a Quarter handled outside the app or a System already started is refused for that first; a refusal rolls the start back, and its entry names the System, and the VA Submission only when one was taken;
   - **submit**;
   - **both review commands**.

   Each of these commands records its step in one place, `Submissions.stepped`, which calls `require` first, and `require` builds the one refusal. `ArchitectureTest.everyCommandThatRecordsAStepAsksSegregationOfDuties` keeps it so for later step commands (tickets 29, 30 and 34): only `Submissions` records a step, any method of it that does must call `require`, and only `stepped` records it.

   A refusal is `403 sod-conflict` with a fixed sentence that names no person and no step. It is audited as the command's own action (`SUBMISSION_STARTED`, `SUBMISSION_SUBMITTED`, `FINDING_REVIEWED`, `REPORT_REVIEWED`) with outcome REFUSED and `reason` `sod-conflict`, in a transaction of its own (ADR 0014).

   The Cyber Manager's inbox and board marker `sodConflict` reads the same rule (`Submissions.conflictedAt`, which replaces `actedAtAnotherStep` and asks `SegregationOfDuties.conflictedAt`), so the marker and the refusal never disagree. The Register already blocks a row whose Cyber Manager is its SMO (story 14). A person can still hold both through a later Register change or a grant made outside the Register, and the guard refuses them.

2. **The order of the checks.** A review checks, in order:
   1. who may see it: the role rule gives `403` to anyone but a Cyber Manager; the access guard on `Need.REVIEW` turns another System's VA Submission or Finding, or one that does not exist, into `404` (ADR 0013);
   2. with the VA Submission's row held, segregation of duties (`403 sod-conflict`);
   3. the state, In Review (`409 invalid-transition`, `reason=state-not-reviewable`);
   4. the fields (`400 invalid-field`, not audited);
   5. with the Finding or the Scan Report held, that what was reviewed is still what is there (item 4).

   A submit checks ownership, then, with the row held, segregation of duties, the state and what stands in the way (ADR 0032).

3. **`ReviewTarget`: the lock and the step.** `Submissions.lockForReview(id, refusedAs)` is the only way to write a review. It runs checks 1 to 3 above, holding the VA Submission's row `FOR SHARE` as an edit does (ADR 0032 item 5). It then records the caller at step `REVIEW`, which is kept only if the review commits. It returns a `ReviewTarget`, which only it can make (private constructor). It also carries the refusal entry, now about the System and the VA Submission, so a later refusal of the same review (`proposal-changed`, `not-reviewable`, `justification-changed`) is recorded alike. Every method of `ReviewStore` but its named reads takes one, so a new write is checked by default (`ArchitectureTest.writesOfAReviewTakeTheReviewLock`).

   The lock order is the edit's: the VA Submission shared first, then the Finding or the Scan Report `FOR UPDATE`. The commands that move a VA Submission out of In Review (tickets 30 and 35) take its row exclusively, so they wait for a review in progress.

4. **A review is an append-only row of what the Cyber Manager read.**
   - **`finding_review`** records the decision (`CONFIRMED` or `DISPUTED`), the comment, who and when. It also records the `proposal_id` reviewed, a proposal of the same Finding (a foreign key on `(proposal_id, finding_id)`). The request names the `proposalId` the screen showed, and a review of one that is no longer current is `409 proposal-changed`, audited. That check, made with the Finding held `FOR UPDATE`, owns the rule; the statement that then sets the Finding's `review_state` repeats no guard. A new proposal puts it back to PENDING (ADR 0027 item 2), so a Finding is CONFIRMED only while the proposal reviewed is its current one.
   - **`report_review`** records the same, plus the justification as it was reviewed (item 5) and its version. `report_slot.justification_version` is a counter. `ScanReports.setMode` decides once, from the row it holds, whether the mode or the justification changed: if so the version goes up and a not-applicable review goes back to PENDING (ADR 0030 item 3), and the same mode and justification set again keep both. The two can therefore never disagree.

     The request names the `justificationVersion` the screen showed (the Scan Reports read gives it), and a review of one that is no longer current is `409 justification-changed`, audited without the text. A counter was chosen over a hash of the wording. It changes on every rewrite, even back to an earlier wording, which the Cyber Manager has not seen since. It is a small number for the screen to send, and it never puts a digest of what a person typed in a request.

     A Scan Report not marked not applicable is `409 not-reviewable`: one entered by hand is reviewed through its Findings.
   - **History.** Both tables are append-only for every connection, by the trigger the Declarations use (V19). Retention (ticket 49) replaces it, as for the proposals. A later review is a new row and the latest one is current. The Findings list gives each Finding's `latestReview` with its `proposalId`, so a reader tells a review of the current proposal from one of an earlier proposal. The Scan Reports read gives `justificationVersion` and, for a not-applicable Scan Report only, its `latestReview`.

5. **The reviewed justification is copied into the review** (decision 2026-10-24). The justification itself stays overwritten in place (ADR 0030 item 3). The statement that writes a review copies the wording and its version from the Scan Report, so after a Dispute and a rewrite the review still shows what was Disputed and why. It is data like the justification: 1 to 4000 characters, never logged or audited.

6. **The comment and the audit.**
   - **The comment** is cleaned by `ReportText.cleanLongText` and bounded to 4000 characters, in code points, as every long text a person types (ADR 0027 item 5). It is required to Dispute and optional to confirm: a confirmation with nothing left after cleaning keeps none. The table checks the bound and that a Dispute has a comment. The screen's comment box is labelled with what it is about, carries the classification label (IM8 dp-8) and the server's `maxLength`, and is linked to the server's problem.
   - **Audit.** A review is `FINDING_REVIEWED` (about the System, the VA Submission and the Finding) or `REPORT_REVIEWED` (with `report_type`), with `decision`, which V21 adds to the detail allow-list. Refusals carry a fixed `reason`: `sod-conflict`, `state-not-reviewable`, `proposal-changed`, `not-reviewable` or `justification-changed`. The comment and the justification never reach a log line, an audit entry or an error body; tests put a sentinel in each and find it in none.

7. **The screen.** `/cyber-manager/submissions/{id}`, linked from each inbox row with a VA Submission, shows:
   - the Scan Reports marked not applicable, flagged, each with its justification, review state and latest review (and the wording reviewed when it has changed since);
   - every Finding, the most severe first, 100 to a page, with Manually Entered Findings flagged (story 52), what was proposed, its review state and its latest review (marked as earlier when it was of another proposal);
   - Confirm and Dispute with a comment.

   The controls show only In Review. Every refusal is the server's problem, shown beside the controls, and after a `409` the screen reads the item again. Everything a person typed is text (the no-raw-HTML gate).

Not done here, on purpose:
- **Edit paths:** the proposals, uploads, scan-date confirmations and mode changes (`lockForEdit`) do not run the segregation-of-duties check. A person refused the submitting step cannot complete it, because submit refuses them.
- **Batch review** (ticket 29), **return for rework and pass to endorsement** (ticket 30), and **re-review of only what changed** (ticket 32).
- **The endorsing step:** a VA POC's scope is every System, so the rule's reading of assignment (`guard.scope(need).ids()`) does not yet say how a VA POC counts. Ticket 30 (or whichever ticket adds the endorsing step) decides it explicitly.
- Any Playwright scenario (ticket 53).

Consequences:
- The marker on the inbox now also counts assignment, so a Cyber Manager who is also a System's SMO/Tech Lead is marked before acting.
- A review screen read before a rewrite or a new proposal cannot confirm what changed.
- The arrange endpoints that set a review state directly stay, for tests that need one on a Draft.
- The review state stays a column (`finding.review_state`, `report_slot.review_state`) beside the latest review row: the commands write both in one transaction, a new proposal or justification resets the column, and the checks under the row locks own the "current" rule. Deriving the state from the rows instead would touch the filters and counts of tickets 19 to 27; ticket 29 (batch review) writes through the same `ReviewStore` methods and adds no other writer.

Amends ADR 0020 (a start is refused for a person who is also the System's Cyber Manager), ADR 0027 item 2 (a Finding's review is a row naming the proposal), ADR 0030 item 3 (the review engine keeps the reviewed wording, and the justification has a version), ADR 0032 (a submit checks segregation of duties) and ADR 0034 item 4 (the marker reads the one rule, `conflictedAt`).
