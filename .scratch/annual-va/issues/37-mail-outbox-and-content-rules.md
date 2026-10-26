# 37: Email through the mail relay with the content rules

**Spec:** [Annual VA spec](../spec.md), stories 91, 94, 88 to 90 (plumbing).
**Release:** 1
**What to build:** Required notifications also go out by email through the organisation's mail relay. A message carries only the System, state, action, due date and a link, so a forwarded email leaks nothing.
**Blocked by:** 36 (In-app notifications and clearing between the two VA POCs)
**Status:** closed
**Effort (orchestrator estimate):** about 14.0 developer days across stories 91 (1.0), 94 (2.0), 88 (10.0), 90 (1.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] REQUIRED notifications are queued for email; a dispatcher sends them through the mail relay boundary (recipient, template key and allow-listed values), retries failures with backoff up to five attempts, and records the relay's message id.
- [x] The relay adapter maps logical template keys to configured template ids and refuses any value outside the allow-list before calling out; no recipient address appears in logs.
- [x] Return for Rework, Disputed and Register-change messages (moved, transferred, withdrawn) reach the SMO; a sentinel Finding never appears in any captured mail; the Seam 1 harness gains a capturing mail relay with a reject-address knob.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `c501cdd`).

**Implemented:** Mail outbox in the same transaction as the notice, `MailRelay` port, retries with backoff, allow-listed template values, no Finding detail. ADR 0046, migration V28 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 957 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/37-mail-outbox-and-content-rules-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0046); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
