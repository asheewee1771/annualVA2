# 68: One rule for "a live report file"

**Spec:** [Annual VA spec](../spec.md), stories 23 and 39 (follow-up raised by the ticket 60 review).
**Release:** 1
**What to build:** Ticket 60 added a soft delete of a report file (`report_file.deleted_at`). Every read that must ignore a deleted file now repeats its own `deleted_at IS NULL` condition: about 15 statements in `ReportFileStore`, `ScanReportStore.HAS_CONTENT`, `ReportFiles.file()` and `ScanPoller.keepFindings`, plus the V47 constraints (the list is in ticket 60's Comments). A future read can forget it and count, serve or settle a deleted file. Give "a live report file" one definition that every such read uses, so a new read cannot miss it, with no change in behaviour.
**Blocked by:** 60 (Delete a report file or a Manually Entered Finding while the VA Submission is editable)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** not estimated; raised by the ticket 60 review of 2026-11-22. Small to medium: a refactor with no behaviour change.

- [ ] One definition of "a live report file" (for example a view, a single predicate constant, or a status the reads already filter on) is used by every read that must ignore deleted files; the reads that must see deleted files (`ReportFileStore.FIND`, `FindingStore.REPLACED_FILE`, the disposal reads) say so explicitly. The definition also covers "no live rescan supersedes it" (not rejected, not deleted, not superseded), which ticket 60 writes in `ReportFileStore.COUNT_LIVE`, `ScanReportStore.HAS_CONTENT` and the `ReportFileStore.DELETE` guard.
- [ ] A test fails when a new read of `report_file` bypasses the definition without saying why (an ArchUnit or source-scan test, or an equivalent guard).
- [ ] No behaviour changes: the existing Seam 1 tests of uploads, rescans, deletes, the 20-file cap, readiness, the mode rule, settlement and disposal pass unchanged in what they assert.
- [ ] ADR 0061 records the definition. The back-end gate passes; the front-end gate applies only if the front end changes.

## Comments

**2026-11-22, raised and approved:** the ticket 60 thermo-nuclear review (T1) found the deleted filter spread over about 15 statements; no single small, safe fix fitted inside ticket 60. The user chose a follow-up ticket, to be built after the current tickets land, so it is `ready-for-agent`.
