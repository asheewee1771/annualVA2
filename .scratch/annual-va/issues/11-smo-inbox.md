# 11: SMO inbox: my Systems and what to do by when

**Spec:** [Annual VA spec](../spec.md), stories 18, 71 (Not Started).
**Release:** 1
**What to build:** An SMO/Tech Lead signs in and sees their Systems for the current Quarter with the state (Not Started for now), the due dates and what is needed next.
**Blocked by:** 10 (Apply a Register preview; SMO and Cyber Manager roles follow the Register)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 8.0 developer days across stories 18 (4.0*), 71 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/dashboards/smo-inbox?cycle=` returns, for the caller's Systems in the selected Cycle, the Quarter, state (Not Started when no VA Submission exists), submit, review and endorse due dates, and the next action; Systems in Quarters handled outside the app are listed under a separate heading with no action.
- [x] A Cyber Manager or VA POC calling the SMO inbox gets 403; an SMO sees nothing of other people's Systems.
- [x] The inbox screen groups Needs your action, Waiting on others and Done, shows Overdue pills when the flag exists, and has a Start button per Not Started System (wired by the next ticket).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-13)

**Implemented** (commits `79ac321`, `d0cbf1b`, `7168a51`, `05e98cd`): `GET /api/dashboards/smo-inbox?cycle=` (SMO only) lists the caller's active Systems for a Cycle with Quarter, state (`NOT_STARTED`, since no VA Submission exists yet), submit, review and endorse due dates, the next action (`START_SUBMISSION`, due on the Quarter's submit date, also for a later Quarter) and its group (`NEEDS_YOUR_ACTION`). Systems in Quarters handled outside the app come back under `handledOutsideApp` with id, name and Quarter only. The caller's Systems come from `RegisterSystems.active(Need.SUBMIT)` through `AccessGuard.scope`, so an SMO sees nothing of others' Systems and a retired System is not listed. Cyber Manager, VA POC, Auditor and a person with no role get 403; no session gets 401. `cycle` is optional (default: the Cycle running today, or the first live Cycle before it starts); a year the app does not run gives 404 and a non-year value 400 without echoing the value. The SMO area now shows the inbox screen in place of the old landing placeholder: Needs your action, Waiting on others and Done, a separate Handled outside the app section, an Overdue pill when the server sends `overdue`, a Cycle selector while two Cycles are open, and a Start button per Not Started System that stays disabled until ticket 12. ADR 0019 records the design.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 323 tests, 0 failures (16 new), ArchUnit and JaCoCo met. The implementer's run; the gate's build check passed it again.
- Front end `npm run check`: passed.
- Mutation check by hand: swapping `submitDue` for `reviewDue` in `SmoInbox.java` failed the due-date test; reverted. The mutation-testing gate was skipped.
- Reviewer: 0 Must-fix, no Human decision.
- `/code-reviewer` final gate: PASS, all seven checks ran (semgrep 1.180.0, 0 findings), no Critical or High finding. Report: `artifacts/code-reviewer/11-smo-inbox-compliance.html`.

All checklist boxes above are ticked.

**Not verified, and open**
- The inbox screen was not viewed in a browser: the mock-m-sso sign-in stack is not on this machine.
- The response has no `overdue` field yet, so a Not Started System past its window is not flagged. Ticket 26 owns the flag and should add the field the front end already reads.
- The inbox read is not audited, following ADR 0018 and the existing decision in `docs/agents/reviewer-decisions.md` that Register list reads are unaudited; revisit if it ever carries report content.
- `SmoInboxScreen` keeps the previous Cycle's data on screen while a newly selected Cycle loads, with no loading indicator, and keeps it beside the error panel if the reload fails.
- `RegisterSystems.active` loads every Register System per request and filters in memory, like the existing list read.
- The endpoint accepts a closed Cycle year; the selector lists only open Cycles.
- Medium and Low findings remain open in the compliance report (thermo-nuclear 4M/5L, code-review 9L, im8 2M/1L, react 1M/4L, springboot 2M/7L).
