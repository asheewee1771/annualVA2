# 39: A rejected address marks the System SMO Unreachable

**Spec:** [Annual VA spec](../spec.md), stories 13.
**Release:** 1
**What to build:** When the mail relay rejects an SMO's address, the System is flagged SMO Unreachable for the VA POC to fix in the Register.
**Blocked by:** 37 (Email through the mail relay with the content rules)
**Status:** closed
**Effort (orchestrator estimate):** about 3.0 developer days across stories 13 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] A rejected-address receipt for an SMO recipient flags every System naming that SMO/Tech Lead as SMO Unreachable with the date, audits it, and shows the flag in the Register table and the VA POC board.
- [x] A later successful send or a Register upload naming a new SMO clears the flag; the limitation that the relay gives no asynchronous bounce feedback is recorded for the PO (ADR 0014).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `cfd4fe0`).

**Implemented:** A refused address flags every System naming it SMO Unreachable; cleared by a later accepted mail or a new SMO; shown to VA POCs. ADR 0053, migration V29 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1070 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/39-bounce-sets-smo-unreachable-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0053); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
