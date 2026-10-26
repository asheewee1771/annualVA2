# 32: Review engine 3: re-review only what changed

**Spec:** [Annual VA spec](../spec.md), stories 56.
**Release:** 1
**What to build:** When a submission comes back, the Cyber Manager re-reviews only the Findings whose proposals changed; everything they confirmed before stays confirmed.
**Blocked by:** 30 (Return for rework or pass to endorsement)
**Status:** closed
**Effort (orchestrator estimate):** about 6.0 developer days across stories 56 (6.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] A resubmitted submission keeps CONFIRMED on Findings whose current proposal is unchanged and shows PENDING only on changed ones; `GET /api/submissions/{id}/findings?changedSinceLastReview=true` lists exactly those.
- [x] Passing to endorsement after a resubmission needs only the changed Findings confirmed; the review screen offers a Changed since last review filter.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `9ffbcb4`).

**Implemented:** A resubmission keeps confirmations; `changedSinceLastReview` filter and review chips; pass needs only the changed Findings confirmed. ADR 0040; README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 881 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/32-re-review-changed-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0040); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
