# 40: Request, freeze and decide an Exemption

**Spec:** [Annual VA spec](../spec.md), stories 43, 44, 45, 46.
**Release:** 1
**What to build:** An SMO requests an Exemption for a System with evidence of a VA within the 3 months before the Quarter or one scheduled within the 3 months after; the submission freezes while a VA POC decides, and a rejection restores it exactly.
**Blocked by:** 34 (Endorse a VA Submission), 36 (In-app notifications and clearing between the two VA POCs)
**Status:** closed
**Effort (orchestrator estimate):** about 15.3 developer days across stories 43 (6.0), 44 (3.0), 45 (2.3), 46 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/systems/{id}/exemptions` (SMO of the System, any state before Endorsed; a Not Started System gets its VA Submission created in the same call) stores basis PRIOR_VA or SCHEDULED_VA, the VA date (validated against the Quarter's 3-month windows), evidence text and optional evidence file, saves the previous state, moves the submission to Exemption Requested and notifies both VA POCs; edits and submit are refused while frozen.
- [x] The exemption request is refused with a 409 problem while any file of the VA Submission is still scanning, including a rescan (ticket 21; decision 2026-10-18), so a settlement never lands in a frozen submission; proven by a Seam 1 test.
- [x] `POST /api/exemptions/{id}/approve` and `/reject` (VA POC, step-up) set Exempted (conditional when the basis is a scheduled VA, with the lapse deadline at the Quarter's end plus 3 months) or restore the previous state untouched (a submission created by the request returns to Not Started); a VA POC who is the System's SMO or Cyber Manager, or acted at another step, is refused with `sod-conflict`; both decisions are audited, notify the SMO, and clear the other VA POC's item.
- [x] `GET /api/dashboards/exemption-queue` lists pending requests; the front end has the request form, the frozen notice and the VA POC queue with Approve and Reject (step-up).
- [x] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `a13955e`).

**Implemented:** Exemption request (freeze, saved previous state), approve and reject (step-up), conditional Exemption for a scheduled VA, VA POC queue. ADR 0048, migration V33 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1002 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/40-exemptions-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0048); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
