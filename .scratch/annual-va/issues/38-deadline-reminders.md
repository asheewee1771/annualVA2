# 38: Deadline reminders for SMOs, Cyber Managers and VA POCs

**Spec:** [Annual VA spec](../spec.md), stories 88, 89, 90.
**Release:** 1
**What to build:** SMOs get reminders 5 days before and on the end of week 2, then weekly while Overdue with the Cyber Manager copied; Cyber Managers get the same pattern against week 4 with the VA POCs copied; VA POCs get reminders 2 weeks and 1 week before the Quarter's last day.
**Blocked by:** 37 (Email through the mail relay with the content rules), 26 (Overdue is a flag: soft windows, nothing locks)
**Status:** closed
**Effort (orchestrator estimate):** about 13.0 developer days across stories 88 (10.0), 89 (2.0), 90 (1.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] A daily planner (08:00 Singapore) computes due reminders from each submission's windows and Overdue state and creates notifications with a dedupe key so a rerun never sends twice; copies go as separate messages since the relay has no cc.
- [x] Seam 1 tests set the clock to each reminder day and run the planner, asserting exactly the expected messages and recipients, and none for Quarters handled outside the app, Withdrawn submissions or final states.
- [x] Before Release 2 gives an added System its own windows, a System added to the Register after its Quarter's Submission Window is flagged Not Started Overdue by the next sweep (ADR 0033 item 3), so its SMO gets the Overdue reminder at once; this is intended, and `va_system.added_on` is not used to hold it back (reviewer decisions, 2026-10-23).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `3e4652e`).

**Implemented:** Daily reminder planner (SMO, Cyber Manager, VA POC pairs, weekly Overdue copies), one reminder per person and day, shared `OpenQuarters`. ADR 0052, migration V38 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 1061 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/38-deadline-reminders-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0052); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
