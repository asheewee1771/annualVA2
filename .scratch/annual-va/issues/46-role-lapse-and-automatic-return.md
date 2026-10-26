# 46: Roles lapse after 90 days and return automatically

**Spec:** [Annual VA spec](../spec.md), stories 80a (lapse), ac-3.
**Release:** 1
**What to build:** A role unused for 90 days lapses and comes back when the Register names the person again, when the Quarter of a System they are named on begins, or when work is handed to them (a Return for Rework, new windows after an Exemption lapse, a transfer). VA POC roles return only through configuration.
**Blocked by:** 10 (Apply a Register preview; SMO and Cyber Manager roles follow the Register), 30 (Return for rework or pass to endorsement), 42 (Conditional Exemption: record the VA, confirm it, or lapse)
**Status:** closed
**Effort (orchestrator estimate):** about 6.0 developer days across stories 80a (6.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] A daily job lapses active grants whose last use (or grant date) is more than 90 days ago; the person's next request is refused and the lapse is audited.
- [x] A Register apply naming the person, the start of a Quarter for a System they are named on, a Return for Rework to them, and new windows after a conditional Exemption lapse un-lapse the grant, audited as an automatic return; VA POC grants return only through configuration.
- [x] Seam 1 tests move the clock 91 days and prove the refusal, then each return trigger and the restored access.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `c2fa966`).

**Implemented:** Role lapse after 90 days and automatic return (Register apply, Quarter start, return for rework, Exemption lapse); a VA POC returns only through configuration. ADR 0055, migration V39 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1088 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/46-role-lapse-and-automatic-return-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0055); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
