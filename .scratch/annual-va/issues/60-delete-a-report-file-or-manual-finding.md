# 60: Delete a report file or a Manually Entered Finding while the VA Submission is editable

**Spec:** [Annual VA spec](../spec.md), stories 23 and 39 (follow-up raised by ticket 22).
**Release:** 1
**What to build:** An SMO can undo a mistake on a Scan Report while the VA Submission is in Draft or Returned for Rework: delete a report file that was uploaded by mistake, or a Manually Entered Finding that was entered by mistake. Once a Scan Report holds nothing, its mode can change again (ADR 0030).
**Blocked by:** 21 (Replace a Scan Report file without losing work), 22 (Mark a Scan Report not applicable, or enter Findings by hand)
**Status:** closed
**Effort (orchestrator estimate):** not estimated; raised by the ticket 22 decision of 2026-10-18 (`docs/agents/reviewer-decisions.md`, "A Scan Report's mode is fixed once it holds anything").

- [x] An SMO of the System can delete a report file (any status other than superseded) or a Manually Entered Finding of a Scan Report while the VA Submission is in Draft or Returned for Rework; any other state is a 409, audited; a stranger or a missing record gets the same 404.
- [x] Deleting a report file removes its occurrences and re-rates the Findings it touched (ADR 0029); a Finding left with no places is removed if it never had a proposal, and otherwise stays and follows the rescan rule (ADR 0029 item 5): it gets a Remediated proposal saying no file of the Scan Report reports it any more, unless it is already Remediated. Proposals and their history are never deleted.
- [x] Deletion is audited with ids and fixed codes only; the retention rules of ticket 49 cover what a deleted record leaves behind.
- [x] When the Scan Report holds no non-rejected file and no Finding after a delete, its mode can be changed again.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

**2026-11-22, issue verification (HITL 2):** the open question is decided by the user: a Finding that deleting a file leaves with no places is removed only if it never had a proposal; one with a proposal stays and follows the rescan rule of ADR 0029. The ticket now uses the glossary term "Manually Entered Finding". It is one vertical slice (endpoints, the screen's delete controls, Seam 1 tests), can be demonstrated alone, and its blockers (21, 22) are built. Tickets 62 and 66 add delete triggers to the append-only history tables: this ticket deletes no proposal or review row, so they do not block it, but its deletes of a Finding must not reach those tables. Approved for execution.

**2026-11-22, built (implementer):** a Manually Entered Finding that has had a proposal cannot be deleted without deleting its proposals, which the ticket forbids, so the delete is refused with 409 `finding-has-history`. The user decided the same day to keep that refusal: such a Finding is deleted only while it has never had a proposal; once it has one, the SMO/Tech Lead proposes a fitting status instead (`docs/agents/reviewer-decisions.md`, "Ticket 60: a Manually Entered Finding that has had a proposal is not deleted"; ADR 0061 item 4). `DeleteManualFindingTest` pins the 409, and the screen offers Delete only on a Finding entered by hand with no status yet.

**2026-11-22, code review correction pass (implementer):** the user accepted the hard delete of a Finding that never had a proposal as an exception to the spec's soft-delete rule (spec lines 285 and 470; `docs/agents/reviewer-decisions.md`, "Ticket 60: a Finding that never had a proposal is hard-deleted, an exception to the soft-delete rule"; ADR 0061 item 3). Thermo-nuclear T1 is left for ticket 68, which the orchestrator raises for it: the "deleted file" rule is not behind one seam (a terminal `DELETED` status or a `report_file_live` view would be), and is written in these places:
- `ReportFileStore` (ingestion): `OF_SUBMISSION`, `COUNT_LIVE`, `SUPERSEDE`, `CONFIRM`, `IN_FLIGHT`, `COUNT_IN_FLIGHT`, `WAITING`, `AS_READ` (shared by `ATTEMPT_STARTED`, `ATTEMPT_FAILED` and `SETTLE`), `LEFTOVERS` (`deleted_at IS NOT NULL`, to sweep a deleted file's quarantine bytes) and `DELETE`.
- `ScanReportStore.HAS_CONTENT` (finding): the mode rule's count of report files.
- `ReportFiles.file()` (ingestion, Java): a deleted file is "not found" for status, metadata, confirm, content, replace and delete.
- `ScanPoller.keepFindings` (ingestion, Java): a failed supersede of a deleted file settles the rescan as an ordinary upload.
- V47: `report_file_deleted_check`, `report_file_quarantine_copy_check` and `report_file_one_live_rescan_idx`.
- Deliberately unfiltered: `ReportFileStore.FIND` (the scan job and the delete need the row), `FindingStore.REPLACED_FILE` (a `FILE_DELETED` proposal names the deleted file) and every `DisposalStore` read of `report_file` (the Cycle's disposal removes deleted files too).

**2026-11-22, second correction pass (implementer):** the user decided the same day that a parsed rescan and then the file it replaced can both be deleted (`docs/agents/reviewer-decisions.md`, "Ticket 60: a parsed rescan and then the file it replaced can both be deleted"; ADR 0061 items 1 and 5). A parsed rescan is deleted by the ordinary rules; once it is gone the file it superseded stays superseded (its Findings are not restored) and can be deleted too, so a Scan Report that has had a rescan can be emptied and its mode unlocked (AC4). The earlier refusal (409 `rescan-not-deletable`) is gone. The migration is renumbered V47, after ticket 66's V45 and ticket 61's V46 on `main`. The places of the "deleted file" rule listed above are ticket 68's (`ReportFileStore.DELETE` now also reads whether the superseding rescan is deleted).
- Third correction pass, 2026-11-22: (1) kept to the decision as the user gave it ("Allow deleting a parsed rescan, and then allow deleting the file it superseded too, so the Scan Report can be emptied"): the superseded file counts as content under the mode rule until it is deleted itself, so `ScanReportStore.HAS_CONTENT` is back to every report file not rejected and not deleted, and the mode unlocks only once both files are gone; (2) a replace now holds the replaced file's row and checks again that it is parsed, not superseded and not deleted before the rescan is kept (`ReportFileStore.LOCK_REPLACEABLE`), so a rescan can no longer be kept for a file a deleted rescan had superseded (409 `file-not-replaceable`).

### Verification (2026-11-23)

**What was built.** While a VA Submission is in Draft or Returned for Rework, an SMO of the System can delete a report file, or a Manually Entered Finding that never had a proposal. In any other state the answer is 409, audited; a stranger or a missing record gets the same 404.
- **Report file deletion.** A report file is deleted softly (`deleted_at`, `deleted_by`): its row and clean content stay until the Cycle is disposed of. Its places are removed and the Findings it touched are re-rated (ADR 0029). A Finding left with no places is removed if it never had a proposal; otherwise it gets a Remediated `FILE_DELETED` proposal unless it is already Remediated. No proposal or history row is deleted.
- **Rescans.** A parsed rescan is deleted like any file. The file it superseded stays superseded, its Findings are not restored, and it can then be deleted too.
- **Mode rule.** A Scan Report's mode can change again once it holds no non-rejected, non-deleted file and no Finding.
- **Concurrency.** A rescan is inserted only while the file it replaces is still parsed, live and not superseded; the check runs after the insert, so it cannot deadlock with a settlement. A rescan whose file was deleted while it was being scanned settles as an ordinary upload.
- **Audit and screen.** Every deletion and refusal is audited with ids and fixed codes only. The screen offers Delete only where it can succeed, with a confirmation.
- **Migration V47.** Renumbered from V45 at merge.
- **Commits.** `bf2f3b4` (feature), `3ff8be9` (ADR 0061, ADR 0021/0029/0030/0050 amendments, decision log, README, ticket), `ca36a96` (README migration table), `020cf1b` (a merge fix: `AuditCompletenessTest` lists eleven actions covered elsewhere with `Map.ofEntries`), `594c1ad` (report). Merged by `178a4d2`.

**Decided by the user on 2026-11-22.**
- A Finding left with no places is removed only if it never had a proposal.
- A Manually Entered Finding that has had a proposal is refused (409 `finding-has-history`).
- The hard delete of a Finding that never had a proposal is accepted as an exception to the spec's soft-delete rule (spec lines 285 and 470).
- "Allow deleting a parsed rescan, and then allow deleting the file it superseded too, so the Scan Report can be emptied."
- The `deleted_at IS NULL` filter spread over many statements is left to follow-up ticket 68 (ready-for-agent, built later). Ticket 68 also covers the "no live rescan supersedes it" rule.

**How it was verified.**
- Seam 1, through the HTTP API on a real PostgreSQL:
  - `DeleteReportFileTest`, 18 tests: each status, places and re-rating, no proposal deleted, a parsed rescan then the file it superseded, the mode rule, a rescan settling after its file was deleted, and a replace that races a settlement and a delete, interleaved through a test hook.
  - `DeleteManualFindingTest`, 5 tests.
  - `DisposalDeletedFileTest`, 1 test: a deleted file's row and content are kept and disposed of with the Cycle.
  - `PermissionMatrixTest`, `StateTableTest` and `AuditCompletenessTest` cover the new endpoints and actions.
- Back-end gate on the branch merged with `main` (`020cf1b`), which differs from merged `main` only in ticket 61's file: `./mvnw -B -ntp clean verify` passed. 1,395 tests, 0 failures, 1 skipped; line coverage 97.17 %, branch 91.52 %; BUILD SUCCESS. Front-end gate: `npm run check` exits 0.
- `/code-reviewer`: PASS, no Critical, High or Medium open (`artifacts/code-reviewer/60-delete-a-report-file-or-manual-finding-compliance.html`). Five rounds and four correction passes; the report says which check saw which tree. Thermo-nuclear's T1 is ticket 68; its N3 was settled by the user's decision.

**Not covered.**
- Race safety rests on the lock order and one interleaved Seam 1 test, not on two real concurrent transactions.
- No browser run was made.
- Lows left open:
  - The confirm button re-enables after a successful delete, so a second press answers 404.
  - There are no CSRF negative tests for the new endpoints.
  - The "parsed, not superseded, not deleted" predicate is written in three statements (ticket 68 territory).
  - Supporting evidence cannot be deleted (no ticket asks for it).

All five boxes are ticked.
