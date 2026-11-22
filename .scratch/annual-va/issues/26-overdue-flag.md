# 26: Overdue is a flag: soft windows, nothing locks

**Spec:** [Annual VA spec](../spec.md), stories 42, 71.
**Release:** 1
**What to build:** When a window passes without the next step, the VA Submission (or a Not Started System) is flagged Overdue, visible to everyone concerned, and the SMO can keep working.
**Blocked by:** 25 (Tick the Declaration and submit for review)
**Status:** closed
**Effort (orchestrator estimate):** about 10.0 developer days across stories 42 (6.0), 71 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] A scheduled sweep flags Not Started and Draft after `submit_due`, In Review and Returned for Rework after `review_due`, and Awaiting Endorsement after `endorse_due`, recording when the flag was set; final states and Quarters handled outside the app are skipped; running the sweep twice changes nothing more.
- [x] An Overdue Draft can still upload, propose and submit; Seam 1 tests move the clock to week 3 and run the sweep.
- [x] The SMO inbox and submission screens show the Overdue pill; setting the flag is audited.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Reviewer log (do-work reviewer, rounds 1-2)

## Findings

| Finding | Required action | Status |
| --- | --- | --- |
| Spec sets Overdue per window (end of week 2 for submitting, end of week 4 for review) but does not say whether the flag clears when a submit moves the VA Submission into a window that has not passed. Implemented as (a): kept only if review_due has also passed, otherwise cleared with no audit entry of its own.<br><br><details><summary><strong>Verify in</strong></summary><br><code>SubmissionStore.java SUBMITTED</code><br><code>ADR 0033 item 4</code></details> | Human decision: (a) keep as built [reviewer recommends]; (b) flag sticks once set; (c) clear it and audit the clearing. | Resolved |
| A System added to the Register after its Quarter's submit_due is flagged Overdue Not Started on the next pass, although story 10 (Release 2) says an added System should not be Overdue the moment it appears. va_system.added_on exists and could be used.<br><br><details><summary><strong>Verify in</strong></summary><br><code> flagNotStarted</code><br><code>ADR 0033 item 3</code></details> | Human decision: (a) keep as built, every non-closed Cycle, until Release 2 mid-cycle windows [reviewer recommends]; (b) skip Quarters whose submit_due is before the System's added_on. | Resolved |


## Human decisions

| Area | Decision | Chosen action | Reuse when |
| --- | --- | --- | --- |
| backend submission, ADR 0033 item 4 | Spec ties Overdue to each window but does not say whether a submit clears a flag set in Draft (KB unavailable). | Do not follow KB recommendation: no KB guidance; user chose (a) on 2026-10-23, keep the flag only if review_due has also passed, otherwise clear it with no separate audit entry. | Any transition that moves a flagged VA Submission into a new window: the flag follows the current window. |
| backend submission sweep, ADR 0033 item 3 | A System added after its Quarter's Submission Window is flagged Not Started Overdue at once; added_on could skip it (KB unavailable). | Do not follow KB recommendation: no KB guidance; user chose (a) on 2026-10-23, sweep every Cycle that is not closed and do not use added_on until Release 2; ticket 38 notes the immediate reminder. | Any finding that an added System is Overdue or reminded too early before Release 2. |


## Full do-work log

Open locally: `artifacts/do-work/26-overdue-flag.html`

Includes reviewer findings and human decisions.

### Verification (2026-10-23)

**Implemented** (commits `34e9fc3`, `9090d1e`, `14c76cf`, `6c10326`):
- **Sweep.** `OverdueSweep` runs one pass in one transaction, at startup and hourly (`OverdueSweepJob`, when scheduling is enabled). It reads the clock once and judges business dates in Asia/Singapore; a window's last day is inside it.
  - One SQL rule, `current_window_due` / `window_passed` (V20), decides the window for every state: Not Started and Draft wait on `submit_due`, In Review and Returned for Rework on `review_due`, Awaiting Endorsement on `endorse_due`, and final or frozen states on none.
  - It flags only what is not flagged yet, so a second pass changes nothing. Rows are claimed `FOR NO KEY UPDATE SKIP LOCKED`, and Not Started flags are inserted `ON CONFLICT DO NOTHING`, so two instances are safe.
  - It skips Quarters handled outside the app, retired Systems (their VA Submissions as well as Not Started) and closed Cycles for Not Started.
- **Storage.** `va_submission.overdue_since`, with `overdue` generated from it, and a `not_started_overdue` table for Systems with no VA Submission.
- **Transitions.** A start takes over the Not Started flag and its time. A submit keeps the flag only if the Review Window has passed too (`OVERDUE_AFTER_MOVE`, which ADR 0033 tells later transition tickets to reuse).
- **Audit.** Each flag writes one `SUBMISSION_OVERDUE` entry with ids and the state only, and no actor.
- **Screens.** The SMO inbox carries `overdue` on every item, Not Started included, and the prepare screen shows "Overdue since …" under the Overdue pill.
- **Docs.** ADR 0033 records the design; ADRs 0019, 0020 and 0032 and the README are amended. Tickets 38 and 49 gained a line each.

**Decisions made (docs/agents/reviewer-decisions.md, 2026-10-23)**
- Overdue clears when a submit moves into a window that has not passed (option (a)).
- Not Started Systems are swept in every Cycle that is not closed, without using `added_on`, until Release 2 (option (a)).
- The sweep skips VA Submissions of retired Systems (option (a)).

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 780 tests, 0 failures, ArchUnit and JaCoCo met. Front end `npm run check`: passed.
- Reviewer: round 1 Must-fix 0 with two human decisions; round 2 after the correction pass Must-fix 0, nothing open. Its log is above.
- `/code-reviewer` round 1: FAIL on one thermo-nuclear High (the current-window rule was written out in four places). After one correction pass, round 2 is a PASS: build and semgrep PASS; thermo-nuclear, code-review, im8, react and springboot WARN; no Critical or High finding. Semgrep's pre-existing test-only High in `TestEndpoints.java` is not counted, as in earlier tickets. Report: `artifacts/code-reviewer/26-overdue-flag-compliance.html`.
- Selective mutation: 12 mutants, 11 killed. The survivor is an equivalent mutant (a scope filter that never removes anything through the API). Report: `artifacts/mutation-testing/26-overdue-flag-mutation.md`.
- Speed rules approved on 2026-10-17 applied: the build check reused the verify log, and round 2 reran thermo-nuclear, build and springboot (the correction changed the data layer).

All checklist boxes above are ticked.

**Not verified, and open**
- code-review, semgrep, im8 and react were not rerun after the final correction pass (the SQL window rule and the generated column).
- Awaiting Endorsement and the final states are set through the test endpoint, since no command enters them yet.
- The Cyber Manager inbox and the VA POC board show the flag in tickets 27 and 33.
- Known and accepted: a start racing a sweep can write a second `SUBMISSION_OVERDUE` entry and a later `overdue_since`; Drafts in a closed Cycle are not excluded (ticket 41); a System moved to another Quarter keeps a Not Started flag (ticket 43).
- No browser run, because sign-in needs `../mock-m-sso`.
- Medium and Low findings remain open in the compliance report.
