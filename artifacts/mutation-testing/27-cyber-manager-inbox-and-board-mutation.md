# Mutation Testing Report: ticket 27 (Cyber Manager inbox and Quarter board)

**Date:** 2026-10-24
**Ecosystem:** Java 21 / Maven (Spring Boot), PostgreSQL (Testcontainers)
**Tool:** custom (no PIT in `pom.xml`; hand-applied mutants, user-approved selective mode)
**Tool Config:** none
**Baseline Command:** `./mvnw -B -ntp test -Dtest='CmInboxTest,CmInboxAccessTest,QuarterBoardTest,SmoInbox*Test' -Djacoco.skip=true` - PASS (39 tests)
**Mutation Command:** per mutant: edit the source, run the same command, restore the original bytes (sha1 checked after every mutant)
**Target Scope:** changed (uncommitted) files, rules guarding access control, input validation, state transitions and data integrity only
**Target:** `CmInboxController`, `QuarterBoardController`, `CmInbox`, `QuarterBoard`, `SmoInbox`, `DashboardSystems`, `Findings.reviewCounts`, `SubmissionStore.ACTED_AT_ANOTHER_STEP`, `VaCycle.quarterShownOn`
**Mutation Threshold:** 70%

## Summary

- First run: 13 mutants, 11 killed, 2 survived (85% raw).
- One survivor (M05, High) was a real gap. Two assertions were added to the Seam 1 tests. M05 and its twin M06 were then run and both were killed.
- Final: **100%** of non-equivalent mutants (14 of 14; 14 of 15 raw, 93%) - **PASS**
- Total: 15 | Killed: 14 | Survived: 1 (confirmed equivalent) | No coverage: 0 | Timeout: 0 | Skipped: 0

## Mutants

| Id | File | Rule | Mutation | Result | Killed by |
|----|------|------|----------|--------|-----------|
| M01 | CmInboxController.java | only a Cyber Manager reads the inbox | `hasRole('CYBER_MANAGER')` to `isAuthenticated()` | Killed | `CmInboxAccessTest` (SMO, VA POC, auditor, no role get 403) |
| M02 | QuarterBoardController.java | only a Cyber Manager reads the board | `hasRole('CYBER_MANAGER')` to `isAuthenticated()` | Killed | `QuarterBoardTest.anSmoAVaPocAnAuditor...Forbidden...` |
| M03 | QuarterBoardController.java | Quarter is 1 to 4, otherwise 400 | `@Max(4)` to `@Max(5)` | Killed | `QuarterBoardTest.aQuarterThatIsNotOneToFourIsRefused...` |
| M05 | CmInbox.java | the inbox reads the REVIEW scope (Systems the caller is Cyber Manager of) | `Need.REVIEW` to `Need.VIEW` | Survived, then killed after test fix | `CmInboxTest.aCyberManagerSeesTheSystemsAssignedToThem...` (after fix) |
| M06 | QuarterBoard.java | the board reads the REVIEW scope | `Need.REVIEW` to `Need.VIEW` | Killed (run after the fix) | `QuarterBoardTest.theQuarterRunningTodayShowsTheCallersSystems...` |
| M07 | SmoInbox.java | the SMO inbox reads the SUBMIT scope | `Need.SUBMIT` to `Need.VIEW` | Killed | `SmoInboxTest.aVaPocWhoIsAlsoNamedAsAnSmoSeesOnlyTheSystemsTheyAreSmoFor` |
| M08 | DashboardSystems.java | the board reads one Quarter only | Quarter filter to `true` | Killed | 2 `QuarterBoardTest` tests |
| M09 | DashboardSystems.java | Quarters Handled outside the app are listed apart | `if (quarter.handledOutsideApp())` to `if (false)` | Killed | 7 tests across CM inbox, board and SMO inbox |
| M10 | CmInbox.java | Overdue first | drop `.reversed()` on `overdue` | Killed | `CmInboxTest.overdueComesFirstAndThenTheDayTheCurrentWindowEnds` |
| M11 | CmInbox.java | a state with no window (`dueOn` null) last | `nullsLast` to `nullsFirst` | Killed | `CmInboxTest.aSubmissionWithTheVaPocsWaitsOnThem...ListedLastWithNoWindow` |
| M12 | Findings.java | `reviewCounts` answers only Systems the caller may VIEW | scope filter to `scope != null` | Survived (equivalent) | - |
| M13 | SubmissionStore.java | segregation-of-duties marker uses only the caller's participation | `user_id = :user` to `(user_id = :user OR TRUE)` | Killed | `CmInboxTest.aSubmissionTheCyberManagerActedOn...`, `QuarterBoardTest.eachSystemCarries...` |
| M14 | CmInbox.java | In Review needs the Cyber Manager's action | `state == IN_REVIEW` to `state == DRAFT` | Killed | 2 `CmInboxTest` tests |
| M15 | VaCycle.java | before the Cycle starts, the board opens on its first live Quarter | `quarters.get(firstLive - 1)` to `quarters.get(0)` | Killed | `QuarterBoardTest.beforeTheFirstLiveCycleStarts...FirstLiveQuarter` |

## Findings

### [F02] M05 Weak assertion: the Cyber Manager inbox scope (High, fixed)

**Target:** `CmInbox.inbox`, `systems.in(cycle, Need.REVIEW)`
**Mutation:** `Need.REVIEW` replaced by `Need.VIEW`
**Evidence:** All 39 tests passed with the mutant. `AccessGuard` gives `VIEW` the union of the caller's SMO and Cyber Manager Systems. No test had a Cyber Manager who was also SMO/Tech Lead of a System they do not review, so the inbox could have listed that System and no test would fail. Story 48 and ADR 0034 say that the inbox lists the Systems assigned to the caller as Cyber Manager. The Quarter board (M06) has the same gap.
**Fix (tests only):**
- `CmInboxTest.aCyberManagerSeesTheSystemsAssignedToThemWithTheirStateAndNothingOfAnotherCyberManagers`: adds `SYS-C4` (SMO `CM`, Cyber Manager `CM_OTHER`, Q3). Asserts that `CM`'s inbox does not contain it and that `CM_OTHER`'s inbox lists `SYS-C3` and `SYS-C4`.
- `QuarterBoardTest.theQuarterRunningTodayShowsTheCallersSystemsInOneColumnPerState`: adds `SYS-BA` (SMO `CM`, Cyber Manager `CM_OTHER`, Q3) and asserts that `CM`'s board does not contain it.
**Verification:** the 39 tests pass on the original code. M05 and M06 are killed by the new assertions.

### [F05] M12 Equivalent mutation: scope filter in `Findings.reviewCounts` (Low)

**Target:** `Findings.reviewCounts`
**Mutation:** `.filter(tally -> scope.contains(tally.systemId()))` replaced by a filter that keeps every tally
**Evidence:** Manual review. The only caller is `ReviewMarkers.of`. It passes the VA Submission ids of rows from `DashboardSystems`. Those rows come from `register.active(Need.REVIEW)` and are filtered again by `Submissions.inCycle` (VIEW scope). `REVIEW` is a subset of `VIEW` (the Cyber Manager's Systems are part of the union), so the filter removes nothing on any reachable path. No HTTP request can observe the mutant. The filter is defence in depth, the same pattern as `Submissions.overdueNotStarted` in ticket 26 (M12 there).
**Recommendation:** Keep the filter. No exclusion is needed (there is no PIT config). Revisit if a second caller passes ids that are not already scoped.

### Considered and not mutated

- `@Min(1)` on `quarter`: the twin of M03. `?quarter=0` → 400 is asserted on the same test line as `?quarter=5`.
- `isFinal()` branch of `CmInbox.group`: `DONE` is asserted for Endorsed and `WAITING_ON_OTHERS` for Awaiting Endorsement and Exemption Requested (M11's test).
- `VaCycle.quarterShownOn` after the last day (`isAfter(endsOn())`): asserted by `anOlderCycleOpensOnItsLastQuarter...`. One mutant (M15) was spent on this method.
- `step <> :step` in `ACTED_AT_ANOTHER_STEP`, empty-list guards, `ReviewMarkers.of(map,row)` null handling, DTO mapping, the column order of the board, and the `REVIEW_COUNTS` filters: secondary or display. They are out of selective scope.

## Remediation Priority

1. [Critical] none
2. [High] M05: fixed by two Seam 1 assertions; M05 and M06 now killed.
3. [Medium] none
4. [Low] M12: confirmed equivalent, documented above.

## Artifacts

- Tool logs: scratchpad `mut27/baseline.log`, `mut27/M01.log` to `M15.log`, `mut27/after-fix-baseline.log` (not kept in the repo)
- Gate: rerun because tests changed. `./mvnw -B -ntp clean verify` returned BUILD SUCCESS: 799 tests, 0 failures, all coverage checks met, 8:44 min. The log is `artifacts/do-work/verify/backend-verify.log`.
- Working-tree fingerprint: `9b1b3672bf2d29369e657231052970ebd9ac427b` before, `45fb19ff0d954dae253dc793e1aa371e6e9f90be` after the test changes (written to `backend-verify.fingerprint`). Every production source was restored byte-for-byte after each mutant (sha1 checked).

## History

- Previous score: unavailable (first run for this ticket)
- Current score: 100% of non-equivalent (14 of 14)
