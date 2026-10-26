# 33: VA POC Quarter board and Cycle matrix

**Spec:** [Annual VA spec](../spec.md), stories 64.
**Release:** 1
**What to build:** A VA POC sees a board for the current Quarter and a matrix of the whole VA Cycle (a strip of the four Quarters, then one row per System with its milestone dots against the due weeks) as two tabs.
**Blocked by:** 27 (Cyber Manager inbox and Quarter board)
**Status:** closed
**Effort (orchestrator estimate):** about 4.0 developer days across stories 64 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/dashboards/quarter-board` for a VA POC covers every System of the Quarter; `GET /api/dashboards/cycle-matrix?cycle=` returns the four-Quarter strip (counts per state, Endorsed Late count, closed, current, upcoming, handled outside the app) and one row per System with its milestone dates (submitted, reviewed, endorsed) against the due weeks; Withdrawn submissions and Quarters handled outside the app are excluded from counts.
- [x] An SMO or Cyber Manager calling the Cycle matrix gets 403.
- [x] The VA POC screens show the Quarter board and Cycle matrix tabs.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `0f3a57b`).

**Implemented:** VA POC Quarter board on the shared reader and the Cycle matrix; milestones `passed_to_endorsement_at` and `endorsed_at`. ADR 0037, migration V24 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 847 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/33-va-poc-board-and-cycle-matrix-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0037); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
