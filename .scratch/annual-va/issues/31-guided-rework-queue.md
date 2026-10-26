# 31: Guided rework queue with Dispute comments and keyboard shortcuts

**Spec:** [Annual VA spec](../spec.md), stories 60, 61, 62.
**Release:** 1
**What to build:** An SMO whose submission was Returned for Rework works through a queue one Finding at a time, Disputed first, sees the Cyber Manager's comment, picks a status with keys 1 to 4 and saves with Enter, then resubmits.
**Blocked by:** 30 (Return for rework or pass to endorsement)
**Status:** closed
**Effort (orchestrator estimate):** about 8.0 developer days across stories 60 (5.0), 61 (2.0), 62 (1.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/submissions/{id}/rework-queue` returns the Findings needing attention in the order Disputed, then new Critical/High pending, then new Medium/Low/Informational pending (the Target Date Missed and Carried Over stages arrive with Release 2), each with the latest Dispute comment and the current proposal.
- [x] A new proposal on a Disputed Finding resets its review state to PENDING; resubmitting through `POST /api/submissions/{id}/submit` moves the submission back to In Review.
- [x] The rework screen shows one Finding at a time with the stage list, the Dispute comment as text, four status buttons bound to keys 1 to 4 and Enter to save and advance (ignored while typing in a field), with a visible shortcut hint and a Resubmit action.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `b425b30`).

**Implemented:** Rework queue in stages for the SMO/Tech Lead with the Dispute and return comment shown, keyboard-driven rework screen, resubmit through the shared Declaration form. ADR 0041; README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 887 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/31-guided-rework-queue-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0041); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
