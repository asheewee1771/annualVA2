# 36: In-app notifications and clearing between the two VA POCs

**Spec:** [Annual VA spec](../spec.md), stories 92, 69.
**Release:** 1
**What to build:** Every user has an in-app list of their notifications; items for the VA POCs go to both and clear for one with "handled by (name)" when the other acts.
**Blocked by:** 34 (Endorse a VA Submission)
**Status:** closed
**Effort (orchestrator estimate):** about 5.4 developer days across stories 92 (2.0), 69 (3.4); `*` marks the critical path. See `reports/dev-report.md`.

- [x] Workflow events create notifications after commit, each carrying only System, state, action, due date and link: submitted notifies the Cyber Manager; returned (by Cyber Manager or VA POC) and Disputed notify the SMO; passed to endorsement notifies both VA POCs; Overdue notifies the person whose step it is; Register changes (moved, transferred, withdrawn) notify the SMO; Exemption requested notifies both VA POCs and the decision notifies the SMO (wired when those tickets land).
- [x] VA POC items are two rows sharing a group key; `GET /api/notifications` lists the caller's notifications with read and cleared state; `POST /api/notifications/{id}/read` marks one read; when a VA POC decides an item, the sibling row is cleared with the handler's name.
- [x] No notification ever contains Finding detail (sentinel test); the Notifications screen lists them for every role.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `f9ea97e`).

**Implemented:** `notification` module: notices after commit for submit, return, pass, Overdue and Register changes, VA POC pairs that clear for both, in-app list and read. ADR 0043, migration V27 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 911 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/36-in-app-notifications-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0043); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
