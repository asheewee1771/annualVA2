# 42: Conditional Exemption: record the VA, confirm it, or lapse

**Spec:** [Annual VA spec](../spec.md), stories 47.
**Release:** 1
**What to build:** For an Exemption based on a future VA, the SMO records that it happened and a VA POC confirms; if it is not recorded within 3 months after the Quarter's end the System is flagged and returned to its previous state with new two-week windows, or becomes Not Endorsed once the Cycle has closed.
**Blocked by:** 40 (Request, freeze and decide an Exemption), 26 (Overdue is a flag: soft windows, nothing locks), 41 (VA Cycle lifecycle: open, overlap, close)
**Status:** closed
**Effort (orchestrator estimate):** about 6.5 developer days across stories 47 (6.5); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/exemptions/{id}/va-happened` (SMO) records the date and suspends the lapse; `POST /api/exemptions/{id}/confirm-va` (VA POC, step-up, `sod-conflict` for a conflicted VA POC) makes the Exemption unconditional; both work after the Cycle has closed for Q4 Systems and both are audited.
- [x] A daily job lapses conditional Exemptions past their deadline with no recorded VA. While the Cycle is open the submission shows its previous state (Not Started when the request created it) with `submit_due` today plus 13 days, `review_due` 14 days later and `endorse_due` the later of the Quarter's end and the Cycle's close, flagged as lapsed on the dashboards; after the Cycle has closed it becomes Not Endorsed with no new windows. Both outcomes are audited and notified.
- [x] The SMO's submission screen has a VA happened form, and the VA POC queue a Confirm VA action (step-up).
- [x] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `b07de57`).

**Implemented:** Record and confirm the VA, hourly lapse job back to the previous state with new windows, Not Endorsed after the close, lapse flag on the dashboards. ADR 0051, migration V37 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1047 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/42-conditional-exemption-lapse-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0051); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
