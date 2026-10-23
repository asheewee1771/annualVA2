# Mutation Testing Report: ticket 26 (Overdue flag)

**Date:** 2026-10-23
**Ecosystem:** Java 21 / Maven (Spring Boot), PostgreSQL migrations (Flyway)
**Tool:** custom (no PIT in `pom.xml`; hand-applied mutants, user-approved selective mode)
**Tool Config:** none
**Baseline Command:** `./mvnw -B -ntp test -Dtest='OverdueTest,SubmitTest,SmoInbox*Test,SubmissionStartTest' -Djacoco.skip=true` - PASS (49 tests)
**Mutation Command:** per mutant: edit the source, run `./mvnw -B -ntp test -Dtest=<relevant classes> -Djacoco.skip=true`, restore the original bytes (sha1 checked after every mutant)
**Target Scope:** changed (uncommitted) files, rules guarding access control, input validation, state transitions and data integrity only
**Target:** `V20__overdue.sql` (`current_window_due`, `window_passed`), `SubmissionStore` (`FLAG_OVERDUE`, `OVERDUE_AFTER_MOVE`, `INSERT`, `FLAG_NOT_STARTED`), `OverdueSweep.sweep`, `Submissions.overdueNotStarted`
**Mutation Threshold:** 70%

## Summary

- Mutation score: **100%** of non-equivalent mutants (11 of 11; 11 of 12 raw, 92%) - **PASS**
- Total: 12 | Killed: 11 | Survived: 1 (confirmed equivalent) | No coverage: 0 | Timeout: 0 | Skipped: 0
- No test changes were needed. Working-tree fingerprint after the run: `bd7ce3e129062351777d214244e642deb229fb6c` (unchanged).

## Mutants

| Id | File | Rule | Mutation | Tests run | Result | Killed by |
|----|------|------|----------|-----------|--------|-----------|
| M01 | V20__overdue.sql | `current_window_due` state to window | In Review waits on `submit_due` instead of `review_due` | OverdueTest | Killed | `aVaSubmissionInReviewIsFlagged...`, `anOverdueDraftCanStill...` |
| M02 | V20__overdue.sql | frozen states wait on nothing | `EXEMPTION_REQUESTED` waits on `endorse_due` | OverdueTest | Killed | `awaitingEndorsement...AndFinalOrFrozenStatesNeverAre` |
| M03 | V20__overdue.sql | `window_passed`: the last day is inside | `due < today` to `due <= today` | OverdueTest | Killed | 7 tests (every last-day check, Singapore midnight) |
| M04 | SubmissionStore.java | `FLAG_OVERDUE` only not-yet-flagged | drop `overdue_since IS NULL` | OverdueTest | Killed | `runningTheSweepAgainChangesNothingMore` and 2 more |
| M05 | SubmissionStore.java | `FLAG_OVERDUE` active Systems only | drop `system_id = ANY(:systems)` | OverdueTest | Killed | `theDraftOfARetiredSystemIsNeverFlagged` |
| M06 | SubmissionStore.java | `OVERDUE_AFTER_MOVE` clears when the new window runs | always keep `overdue_since` | OverdueTest, SubmitTest | Killed | `anOverdueDraftCanStill...NotOverdueInReview...` |
| M07 | SubmissionStore.java | `OVERDUE_AFTER_MOVE` keeps when the new window passed | always clear (`overdue_since = NULL`) | OverdueTest, SubmitTest | Killed | `anOverdueDraftSubmittedAfterTheReviewWindowStaysOverdue...` |
| M08 | SubmissionStore.java | start `INSERT` takes over the Not Started flag | `THEN (SELECT overdue_since ...)` to `THEN CAST(NULL AS timestamptz)` | OverdueTest | Killed | `aVaSubmissionStartedForAnOverdueNotStartedSystem...` |
| M09 | SubmissionStore.java | `FLAG_NOT_STARTED` only Systems with no VA Submission | drop `NOT EXISTS (va_submission ...)` | OverdueTest, SmoInbox*Test | Killed | 8 tests (extra NOT_STARTED audit entry) |
| M10 | OverdueSweep.java | closed Cycles skipped for Not Started | `if (false && phase == CLOSED)` | OverdueTest | Killed | `aNotStartedSystemIsFlaggedWhileItsCycleIsClosingButNotOnceTheCycleHasClosed` |
| M11 | OverdueSweep.java | Quarters Handled outside the app skipped | `if (!quarter.handledOutsideApp())` to `if (true)` | OverdueTest | Killed | `aNotStartedSystemIsFlagged...ShownOverdueInTheInbox` |
| M12 | Submissions.java | `overdueNotStarted` answers only Systems the caller may see | drop `filter(scope::contains)` | OverdueTest, SmoInbox*Test | Survived (equivalent) | - |

Note on M08: a first version (`THEN NULL`) was killed only by a 500 (Postgres typed the bare `NULL` as text). It was rerun well-typed so the kill is semantic; the table records the rerun.

## Findings

### [F05] M12 Equivalent mutation: scope filter in `Submissions.overdueNotStarted` (Low)

**Target:** `Submissions.overdueNotStarted`
**Mutation:** `systemIds.stream().filter(scope::contains).toList()` replaced by `List.copyOf(systemIds)`
**Evidence:** Manual review. The only caller is `SmoInbox.inbox`, which passes ids from `register.active(Need.SUBMIT)`. `AccessGuard.scope` gives `SUBMIT` the caller's SMO Systems and `VIEW` the union of SMO and Cyber Manager Systems (or everything for a VA POC), so `SUBMIT` is a subset of `VIEW` and the filter removes nothing on any reachable path. No HTTP request can observe the mutant. The filter is defence in depth, the same pattern as `Submissions.inCycle`.
**Recommendation:** Keep the filter; no exclusion needed (no PIT config). Revisit if a second caller passes ids not already scoped.

### Considered and not mutated

- `flagOverdue` / `flagNotStarted` / `notStartedOverdue` empty-list guards: removing them runs `= ANY('{}')`, which matches nothing, so the result is the same empty list. Equivalent by inspection; a performance shortcut, not a rule.
- Retired Systems for Not Started: covered by `RegisterSystems.everyActive` (pre-existing `!system.retired()` filter) and asserted for `SYS-N5`; M05 covers the retired guard on the VA Submission side.
- `OverdueSweepJob`, audit construction, DTO and inbox mapping: plumbing or display, out of selective scope.

## Remediation Priority

1. [Critical] none
2. [High] none
3. [Medium] none
4. [Low] M12: confirmed equivalent, documented above.

## Artifacts

- Tool logs: scratchpad `mut26/baseline.log`, `mut26/M01.log` to `M12.log` (not kept in the repo)
- Gate: not rerun (no test changes); `artifacts/do-work/verify/backend-verify.log` and its fingerprint still stand

## History

- Previous score: unavailable (first run for this ticket)
- Current score: 100% of non-equivalent (11 of 11)
