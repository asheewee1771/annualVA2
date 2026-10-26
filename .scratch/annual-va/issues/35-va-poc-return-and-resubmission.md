# 35: VA POC return with comments; resubmission goes through the Cyber Manager

**Spec:** [Annual VA spec](../spec.md), stories 66, 63.
**Release:** 1
**What to build:** A VA POC returns a submission to the SMO with comments to challenge something the Cyber Manager confirmed; the SMO's resubmission goes through the Cyber Manager again, who re-reviews only what changed, so the chain of confirmation stays whole.
**Blocked by:** 34 (Endorse a VA Submission), 32 (Review engine 3: re-review only what changed)
**Status:** closed
**Effort (orchestrator estimate):** about 8.6 developer days across stories 66 (6.6), 63 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/submissions/{id}/return` by a VA POC (step-up, Awaiting Endorsement) moves the state to Returned for Rework, requires a comment, stores the return, audits and publishes required notifications to the SMO and the Cyber Manager; a VA POC conflicted by segregation of duties is refused with `sod-conflict`.
- [x] Returned for Rework after a VA POC return is Overdue after `review_due` like any other; resubmitting moves to In Review and the Cyber Manager re-reviews only what changed, including after this VA POC return.
- [x] The endorsement view offers Return with a comment, and the SMO's rework queue shows the VA POC's comment.
- [x] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `7ef180d`).

**Implemented:** VA POC return with a required comment (step-up), shared return path, notices to the SMO and the Cyber Manager, comment kept across the step-up. ADR 0047, migration V26 and V31 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 950 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/35-va-poc-return-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0047); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
