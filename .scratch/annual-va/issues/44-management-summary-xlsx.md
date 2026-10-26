# 44: Management summary export (Excel), watermarked, no vulnerability detail

**Spec:** [Annual VA spec](../spec.md), stories 81, 86.
**Release:** 1
**What to build:** A VA POC requests the management summary with a fresh sign-in, it is generated in the background and downloaded as Excel with its classification watermark, containing counts and a by-System table but no vulnerability detail.
**Blocked by:** 34 (Endorse a VA Submission), 33 (VA POC Quarter board and Cycle matrix)
**Status:** closed
**Effort (orchestrator estimate):** about 5.3 developer days across stories 81 (3.3), 86 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/exports` with type MGMT_SUMMARY and format XLSX (VA POC, step-up) queues an export; it is produced in the background with counts by Quarter and state, Endorsed Late, and a by-System table (SMO, state, Overdue, number of open Deviations), excluding Withdrawn submissions and Quarters handled outside the app, stored encrypted with a 24-hour expiry.
- [x] `GET /api/exports/{id}` reports status; `GET /api/exports/{id}/download` (requester only) serves the attachment and audits the download; the file carries its classification in header, footer and first row: Confidential when it covers the complete set (all Systems of a Quarter or Cycle), otherwise Restricted / Sensitive-Normal (assumption A-1).
- [x] A sentinel Finding never appears in the export; the Exports screen requests, polls and downloads.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `20af740`).

**Implemented:** Management summary in Excel through `POST /api/exports` (step-up), 24-hour requester-only files, classification on every sheet, no vulnerability detail. ADR 0045, migration V32 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 936 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/44-management-summary-xlsx-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0045); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
