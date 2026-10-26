# 34: Endorse a VA Submission

**Spec:** [Annual VA spec](../spec.md), stories 65, 67, 70.
**Release:** 1
**What to build:** A VA POC endorses an Awaiting Endorsement submission with a fresh sign-in (Endorsed Late after the Quarter's last day); a VA POC who acted at another step on it is blocked so the other VA POC endorses.
**Blocked by:** 30 (Return for rework or pass to endorsement), 09 (Step-up: a fresh sign-in before sensitive actions)
**Status:** closed
**Effort (orchestrator estimate):** about 8.7 developer days across stories 65 (4.0), 67 (2.0), 70 (2.7); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/submissions/{id}/endorse` (VA POC, step-up, Awaiting Endorsement) sets Endorsed, marks Endorsed Late when today's business date is after `endorse_due`, records the deciding step, audits and publishes an FYI event.
- [x] A VA POC who acted at the submitting or reviewing step on that submission, or who is the System's SMO or Cyber Manager, is refused with 403 `sod-conflict`; the other VA POC can then endorse, and a test proves both halves.
- [x] The endorsement view shows the read-only Findings, Endorse (step-up) and the Endorsed Late marker.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `1e60b3b`).

**Implemented:** Endorse (step-up, `endorsed_late`, `endorsed_at`), the VA POC segregation-of-duties rule, one `Submissions.atStep` gate, the endorsement view. ADR 0042, migration V25 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 896 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/34-endorse-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0042); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
