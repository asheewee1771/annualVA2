# 50: Completeness: every audited action and every row of the state table

**Spec:** [Annual VA spec](../spec.md), stories 84, state table, Testing Decisions.
**Release:** 1
**What to build:** Two parameterised Seam 1 suites close the Testing Decisions: every action named in the audit story writes its entry, and every state of the VA Submission answers every command exactly as the state table says.
**Blocked by:** 40 (Request, freeze and decide an Exemption), 41 (VA Cycle lifecycle: open, overlap, close), 42 (Conditional Exemption: record the VA, confirm it, or lapse), 43 (Register preview effects on in-flight work: withdraw, and block the rest), 44 (Management summary export (Excel), watermarked, no vulnerability detail), 47 (Role-holder review, suspension and identity-provider disablement), 49 (Retention: disposal list, approval and hard delete)
**Status:** closed
**Effort (orchestrator estimate):** about 7.0 developer days across stories 84 (7.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] A parameterised test walks every audited action (view, download, upload, status change, confirmation, Dispute, return, endorsement, Exemption decision, Register change, role change, export, disposal) and asserts its entry with actor, role, outcome and ids and without detail text.
- [x] A parameterised test drives every state of the state table with every command: allowed transitions succeed; disallowed ones answer 409 `invalid-transition`; Endorsed, Exempted, Withdrawn and Not Endorsed are read-only; Exemption Requested is frozen; the Overdue flag is set exactly where the table says.
- [x] A permission-matrix test covers each role against each action (200, 401, 403, 404) and the step-up list (stale and fresh), per the implementation plan section 6; every refusal row also asserts its audit entry.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `56bd767`).

**Implemented:** Audit and state-table completeness suites (every audited action, 10 states and 34 moves, 63-endpoint permission matrix), audit-log reads audited, session-store ordering bug fixed. ADR 0057, migration V41 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1233 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/50-audit-and-state-table-completeness-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0057); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
