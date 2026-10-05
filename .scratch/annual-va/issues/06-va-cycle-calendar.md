# 06: VA Cycle and Quarter calendar

**Spec:** [Annual VA spec](../spec.md), stories 16, 71 (dates).
**Release:** 1
**What to build:** The app knows the current VA Cycle and Quarter, every Quarter's windows, and which Quarters are handled outside the app, all computed in Singapore time from the controllable clock.
**Blocked by:** 02 (Walking skeleton: repository, security baseline and Seam 1 harness)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 13.1 developer days across stories 16 (9.1), 71 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] A VA Cycle is named after its starting year and has four Quarters starting on the first Monday of February, May, August and November; a Quarter ends the day before the next Quarter starts (12 to 14 weeks), and Q4 ends the day before the next Cycle's Q1.
- [ ] Each Quarter exposes `submit_due` (start plus 13 days), `review_due` (start plus 27 days) and `endorse_due` (the Quarter's last day); the Cycle's `closes_on` defaults to Q4's end plus 28 days.
- [ ] `first_live_quarter` (Cycle 2027 is Q3) marks earlier Quarters as Handled outside the app; a property-based test over the years 2026 to 2035 proves the first-Monday and end-of-Quarter rules.
- [ ] `GET /api/cycles` and `GET /api/cycles/{year}` return the Cycle, its Quarters, windows and the current Quarter and week for today's business date.
- [ ] The front end header shows Cycle, Quarter, week number and the current window.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
