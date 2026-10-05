# The VA Cycle and Quarter calendar is computed from the clock, not stored

Status: accepted

The calendar is a pure function of the year, one setting and the `Clock` bean. A Quarter starts on the first Monday of February, May, August or November and ends the day before the next one starts (the next Cycle's Q1 for Q4); `submit_due` is the start plus 13 days, `review_due` the start plus 27 days, `endorse_due` the Quarter's last day, and a Cycle's `closes_on` defaults to Q4's end plus 28 days. All of it is in `Asia/Singapore` (ADR 0006). `GET /api/cycles` lists the Cycles the app runs (from the first live Cycle to the one running today) and `GET /api/cycles/{year}` answers any year from 2000 to 2099.

Three decisions follow.

1. **No table yet.** The implementation plan sketched a `va_cycle` table. Every value in it for this ticket is derivable, so storing it would only add a second source of truth that a "new Cycle opens automatically" job would have to keep in step. The table arrives with the first thing that cannot be derived: a VA POC closing a Cycle early (ticket 41), which then stores only `closed_at` and `closed_by` and overrides `closes_on`.
2. **The first live Quarter is configuration.** `annualva.calendar.first-live-cycle` (2027) and `first-live-quarter` (3) are validated at startup, and the application refuses to start without them. Quarters before them, and every earlier Cycle, are `handledOutsideApp`: left out of counts, reminders and the Cycle close by the tickets that count, remind and close. If a VA POC must change the setting without a deployment, that ticket replaces the two properties by a stored setting.
3. **A Cycle is open through its close date.** `phase` is `UPCOMING` before Q1, `RUNNING` through Q4's last day, `CLOSING` through `closes_on` and `CLOSED` the day after, the same inclusive reading as "endorsement is due on the Quarter's last day". The Cycle close ticket confirms or changes this single rule in `VaCycle.phaseOn`.

The calendar names no System and holds no report content, so its endpoints use a role rule (any of the four roles) and neither `AccessGuard` (ADR 0013) nor an audit entry (ADR 0014: reads are not actions).

Consequences: tests set "week N of Quarter Q" by moving the clock and read the answer through the HTTP API; the property over the years 2026 to 2035 is checked there too.
