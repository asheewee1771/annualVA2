# 48: Cyber Manager audit history

**Spec:** [Annual VA spec](../spec.md), stories 85.
**Release:** 1
**What to build:** A Cyber Manager reads the audit history of the VA Submissions they reviewed, on Systems still assigned to them.
**Blocked by:** 28 (Review engine 1: segregation of duties and single confirm or Dispute), 05 (Append-only audit log with refused-request logging)
**Status:** closed
**Effort (orchestrator estimate):** about 3.3 developer days across stories 85 (3.3); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/audit-entries?submission=` for a Cyber Manager returns entries only for submissions where they acted at the reviewing step on Systems still assigned to them; any other submission answers 404.
- [x] The audit log viewer is available on the Cyber Manager screens with the same filters and no Finding detail.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `1832ce9`).

**Implemented:** Cyber Manager reads the audit history of the VA Submissions they reviewed through a port the submissions answer; Audit history tab. ADR 0038; README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 852 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/48-cm-audit-history-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0038); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
