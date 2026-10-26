# 49: Retention: disposal list, approval and hard delete

**Spec:** [Annual VA spec](../spec.md), stories 87.
**Release:** 1
**What to build:** Records due for disposal (Cycle plus 3 years, superseded files once Endorsed, expired exports) are listed; a VA POC approves the list with a fresh sign-in; the app hard-deletes them and logs it.
**Blocked by:** 44 (Management summary export (Excel), watermarked, no vulnerability detail), 21 (Replace a Scan Report file without losing work)
**Status:** closed
**Effort (orchestrator estimate):** about 14.5 developer days across stories 87 (14.5); `*` marks the critical path. See `reports/dev-report.md`.

- [x] A daily job computes due records and writes a DRAFT disposal list with items (submissions, files, superseded files, exports, audit ranges) and reasons; `GET /api/disposal-lists` shows it.
- [x] `POST /api/disposal-lists/{id}/approve` (VA POC, step-up) marks it APPROVED; the executor deletes file-store objects then records, removes audit entries only within the approved range, and writes one tombstone audit entry with counts and the list id; any other removal before retention is a soft delete.
- [x] The executor also disposes of `finding_status_proposal` and `bulk_batch`, which have append-only triggers (V14, ADR 0027; V15, ADR 0028) with no bypass: a later migration replaces both triggers, and the delete runs in foreign-key order (clear `finding.current_proposal_id`, delete the proposals, then the batches, then the Findings and files); a Seam 1 test proves proposals and batches are gone after an approved disposal.
- [x] A superseded file listed once its VA Submission is Endorsed (ticket 21, ADR 0029 item 7) is disposed of early by deleting only its bytes from the file store: its `report_file` row stays until the Cycle-plus-3-years disposal, because `finding_status_proposal.replaced_file_id`, `report_file.replaces_id` and `report_file.superseded_by` reference it, and a download of it then answers with a plain "disposed" problem; the full disposal deletes rows in an order that satisfies those foreign keys (reviewer decisions, 2026-10-18).
- [x] The executor also deletes the `not_started_overdue` rows (V20, ADR 0033) of a System or Cycle it disposes of: they carry a foreign key to `va_system`, so they go before the System's row, in the same foreign-key order as the rest (ticket 26 review, 2026-10-23).
- [x] The Disposal screen shows the list for review with Approve (step-up); Seam 1 tests move the clock past Cycle plus 3 years and prove the list, the approval, the deletion and the tombstone.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `b324765`).

**Implemented:** Daily disposal list, VA POC approval (step-up), executor deleting files before records, one tombstone entry, superseded files purged once Endorsed. ADR 0050, migration V36 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1032 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/49-retention-and-disposal-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0050); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
