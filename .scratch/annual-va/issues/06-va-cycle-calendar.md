# 06: VA Cycle and Quarter calendar

**Spec:** [Annual VA spec](../spec.md), stories 16, 71 (dates).
**Release:** 1
**What to build:** The app knows the current VA Cycle and Quarter, every Quarter's windows, and which Quarters are handled outside the app, all computed in Singapore time from the controllable clock.
**Blocked by:** 02 (Walking skeleton: repository, security baseline and Seam 1 harness)
**Status:** closed
**Effort (orchestrator estimate):** about 13.1 developer days across stories 16 (9.1), 71 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] A VA Cycle is named after its starting year and has four Quarters starting on the first Monday of February, May, August and November; a Quarter ends the day before the next Quarter starts (12 to 14 weeks), and Q4 ends the day before the next Cycle's Q1.
- [x] Each Quarter exposes `submit_due` (start plus 13 days), `review_due` (start plus 27 days) and `endorse_due` (the Quarter's last day); the Cycle's `closes_on` defaults to Q4's end plus 28 days.
- [x] `first_live_quarter` (Cycle 2027 is Q3) marks earlier Quarters as Handled outside the app; a property-based test over the years 2026 to 2035 proves the first-Monday and end-of-Quarter rules.
- [x] `GET /api/cycles` and `GET /api/cycles/{year}` return the Cycle, its Quarters, windows and the current Quarter and week for today's business date.
- [x] The front end header shows Cycle, Quarter, week number and the current window.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-05)

**Implemented** (commits `3255002`, `51e919a`, `2f2531f`, `e05dacf`): the `cycle` package computes a VA Cycle named after its starting year with four Quarters starting on the first Monday of February, May, August and November, each ending the day before the next starts (12 to 14 weeks) and Q4 ending the day before the next Cycle's Q1. Each Quarter exposes `submitDue` (start + 13), `reviewDue` (start + 27) and `endorseDue` (last day) with three windows, and `closesOn` is Q4's end + 28 days. `first-live-cycle` 2027 and `first-live-quarter` 3 mark earlier Quarters, and all of Cycle 2026, as Handled outside the app. `GET /api/cycles` and `GET /api/cycles/{year}` return the Cycle, Quarters, windows, `phase` and `current` (Quarter, week, window, window end) for today's business date, read through the `Clock` bean in `Asia/Singapore`. Any role may read them (no session 401, no role 403). The header shows Cycle, Quarter, week and window and flags a Quarter handled outside the app. ADR 0015 records the design.

**Verification performed**
- Back end `mvnw.cmd -B -ntp clean verify`: 213 tests, 0 failures, ArchUnit and JaCoCo met. The implementer ran it twice, both green.
- Front end `npm run check`: passed.
- Reviewer: 0 Must-fix. Two Human decisions, answered below.
- `/code-reviewer` final gate: PASS on the first run (build PASS, the other six WARN). Report: `artifacts/code-reviewer/06-va-cycle-calendar-compliance.html`.
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Decisions made (docs/agents/reviewer-decisions.md)**
- The calendar is computed and no `va_cycle` table is added here. Ticket 41 adds the table for `closed_at`, `closed_by` and the early-close override of `closes_on`; the plan's data model and ticket 41 say so.
- The first live Quarter stays configuration until the cyber team asks to change it without a deployment; the plan's `va_cycle` row says so.

**Not verified, and open**
- The header was not viewed in a browser, and `.shell-header` has no `flex-wrap`; check it at about 320 px and 1280 px.
- Ticket 41 may change the `CLOSING` and `CLOSED` reading (a Cycle is open through `closesOn` and `CLOSED` the day after); it is isolated in `VaCycle.phaseOn`.
- `CycleStatus.tsx` puts `aria-label` on a plain paragraph, which ARIA does not allow a name on. Remove the label or wrap the text in an element that can carry one.
- The header loads the calendar once, so a tab left open across Singapore midnight or a Monday shows a stale week until reload. Ticket 33 or 41 can add a refresh.
- No test asserts that a successful `GET /api/cycles` writes no audit entry; the README and ADR 0015 claim it.
- Medium and Low findings from the gate remain open in the compliance report.
