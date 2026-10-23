# Mutation Testing Report: ticket 25 (Declaration and submit)

**Date:** 2026-10-23
**Ecosystem:** Java 21 / Maven (Spring Boot)
**Tool:** custom (no PIT in `pom.xml`; hand-applied mutants, user-approved selective mode)
**Tool Config:** none
**Baseline Command:** `./mvnw -B -ntp test -Dtest=SubmitTest,SubmitRulesTest,FileAgeReadinessTest,ScanDateConfirmTest -Djacoco.skip=true` - PASS (34 tests)
**Mutation Command:** per mutant: edit the source, run `./mvnw -B -ntp test -Dtest=<relevant classes> -Djacoco.skip=true`, restore the original bytes (checked)
**Target Scope:** changed (uncommitted) files, rules guarding access control, input validation, state transitions and data integrity only
**Target:** `Submissions`, `SubmitChecks`, `FileReadiness`, `SubmissionStore` (current Declaration query)
**Mutation Threshold:** 70%

## Summary

- First run: 6 of 10 killed (60%), WARN. Four survivors, all High or Critical, fixed by strengthening Seam 1 tests.
- After the test changes: **100%**, PASS. Total: 10 | Killed: 10 | Survived: 0 | No coverage: 0 | Timeout: 0 | Skipped: 0

## Mutants

| Id | File | Rule | Mutation | First run | After fix |
|----|------|------|----------|-----------|-----------|
| M01 | submission/Submissions.java | editable states | `editable` accepts DRAFT only (drop RETURNED_FOR_REWORK) | Killed | Killed |
| M02 | submission/Submissions.java | lockForEdit state check | `held` checks the state only for a submit (`forSubmit && !editable`) | Killed | Killed |
| M03 | submission/Submissions.java | ownership (`owned`) | `guard.requireSystem(..., Need.SUBMIT)` removed | **Survived** | Killed |
| M04 | submission/Submissions.java | declaration-not-accepted | `!TRUE.equals(accepted)` to `FALSE.equals(accepted)` (null counts as ticked) | Killed | Killed |
| M05 | submission/Submissions.java | declaration-changed | `version == null \|\| version != current` to `version == null` | Killed | Killed |
| M06 | finding/SubmitChecks.java | files-scanning | `!scanning.isEmpty()` to `scanning.size() > 1` | **Survived** | Killed |
| M07 | finding/SubmitChecks.java | report-missing | `mode == UPLOAD` to `mode != MANUAL` (not applicable counts as missing) | Killed | Killed |
| M08 | finding/SubmitChecks.java | findings-unproposed | `unproposed > 0` to `unproposed > 1` | **Survived** | Killed |
| M09 | ingestion/FileReadiness.java | still checked | rejected files count as still being checked | Killed | Killed |
| M10 | submission/SubmissionStore.java | current Declaration is the highest | `ORDER BY version DESC` to `ORDER BY version` | **Survived** | Killed |

## Findings

### [F02] M03 Weak assertion: ownership check on submit (Critical, fixed)

**Target:** `Submissions.owned` (used by `submit` and `lockForEdit`)
**Evidence:** another System's SMO still got 404 only because `view(id)` at the end of `submit` threw and rolled the transaction back. A refused submit (not ready) by another System's SMO answered 409 `not-ready` and wrote a REFUSED audit entry, disclosing the VA Submission (ADR 0013).
**Fix:** `SubmitTest.onlyTheSystemsOwnSmoSubmitsIt` also submits unticked as SMO_B, expects 404, and expects no `SUBMISSION_SUBMITTED` entry at all.

### [F03] M06 Boundary gap: one file still being checked (High, fixed)

**Evidence:** the only scanning case had two files.
**Fix:** `SubmitRulesTest.aFileStillBeingCheckedBlocksSubmittingEvidenceAndARescanIncluded` asserts `files-scanning` naming just the rescan before the evidence is uploaded.

### [F03] M08 Boundary gap: one Finding without a proposal (High, fixed)

**Evidence:** the only unproposed case had two Findings.
**Fix:** `SubmitRulesTest.everyFindingNeedsAProposedStatus` proposes one, then expects `findings-unproposed` with "1 Finding has".

### [F01] M10 No coverage: the current Declaration is the highest version (High, fixed)

**Evidence:** only version 1 exists, so the order of the query was never observable; the app would show, accept and store the placeholder after the agreed wording is added.
**Fix:** test-only endpoint `POST /api/test/declaration-text` (`Arrange.declarationVersion()`) adds the next version; the register reset removes the versions a test added (append-only trigger disabled for that delete only). New `SubmitTest.theCurrentDeclarationIsTheHighestVersionAndOnlyItIsAcceptedAndKept`: the new version is shown and not a placeholder, version 1 is refused as `declaration-changed`, and the submit stores the new version's wording.

## Remediation Priority

1. [Critical] M03: fixed.
2. [High] M06, M08, M10: fixed.

## Artifacts

- Tool logs: scratchpad `mut/M01.log` to `M10.log` (not kept in the repo)
- Gate: `artifacts/do-work/verify/backend-verify.log`

## History

- Previous score: unavailable
- Current score: 100% (10 of 10)
