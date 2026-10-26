# 47: Role-holder review, suspension and identity-provider disablement

**Spec:** [Annual VA spec](../spec.md), stories 80a (removal), ac-3, ac-4.
**Release:** 1
**What to build:** The VA POCs review every role holder (SMO/Tech Leads, Cyber Managers, VA POCs) against the Register baseline at each Register upload and at least quarterly, see the automatic returns, and may suspend a person's role for a System with a fresh sign-in; a person disabled at the identity provider loses access at once.
**Blocked by:** 46 (Roles lapse after 90 days and return automatically)
**Status:** closed
**Effort (orchestrator estimate):** about 6.0 developer days across stories 80a (6.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/role-holders` lists every holder against the Register baseline with the automatic returns since the last review, and flags when no review has been recorded in 3 months; `POST /api/role-holders/reviews` (VA POC) records a completed review, audited.
- [x] `POST /api/role-holders/{grantId}/suspend` (VA POC, step-up) suspends a role for a System until a Register upload names someone; a VA POC cannot suspend or restore their own grant (403); suspensions are audited.
- [x] A person reported disabled at the identity provider (the sign-in callback or a session check sees the disabled status) has every grant suspended immediately and their session ended.
- [x] The Role-holder review screen lists holders, shows the review-due flag and offers Review completed and Suspend (step-up).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `7f95ac2`).

**Implemented:** Role-holder review list and record, suspend and restore (step-up), people disabled at the identity provider end all grants and sessions. ADR 0056, migration V40 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1108 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/47-role-holder-review-and-suspension-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0056); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
