# 29: Review engine 2: confirm or Dispute a Bulk-set batch

**Spec:** [Annual VA spec](../spec.md), stories 51, 52.
**Release:** 1
**What to build:** Bulk-set Findings are grouped by batch and the Cyber Manager confirms or Disputes a whole batch at once; Manually Entered Findings stay clearly marked for closer review.
**Blocked by:** 28 (Review engine 1: segregation of duties and single confirm or Dispute)
**Status:** closed
**Effort (orchestrator estimate):** about 6.0 developer days across stories 51 (5.0), 52 (1.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/submissions/{id}/batches` lists batches with their status, detail summary and member counts; `POST /api/batches/{id}/reviews` applies CONFIRMED or DISPUTED (with one comment) to every member whose current proposal belongs to the batch and is PENDING, and is audited as one batch review.
- [x] Members changed individually since the batch are left out of the batch review and listed in the response.
- [x] The review screen groups batch members under their batch with Confirm batch and Dispute batch actions.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `d84d80a`).

**Implemented:** Batch list and batch review reusing ticket 28's review rows, lock and segregation-of-duties rule; left-out members listed; review screen groups members under their batch. ADR 0036, migration V22 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 840 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/29-batch-review-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0036); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
