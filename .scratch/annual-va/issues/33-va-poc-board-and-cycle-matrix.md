# 33: VA POC Quarter board and Cycle matrix

**Spec:** [Annual VA spec](../spec.md), stories 64.
**Release:** 1
**What to build:** A VA POC sees a board for the current Quarter and a matrix of the whole VA Cycle (a strip of the four Quarters, then one row per System with its milestone dots against the due weeks) as two tabs.
**Blocked by:** 27 (Cyber Manager inbox and Quarter board)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 4.0 developer days across stories 64 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/dashboards/quarter-board` for a VA POC covers every System of the Quarter; `GET /api/dashboards/cycle-matrix?cycle=` returns the four-Quarter strip (counts per state, Endorsed Late count, closed, current, upcoming, handled outside the app) and one row per System with its milestone dates (submitted, reviewed, endorsed) against the due weeks; Withdrawn submissions and Quarters handled outside the app are excluded from counts.
- [ ] An SMO or Cyber Manager calling the Cycle matrix gets 403.
- [ ] The VA POC screens show the Quarter board and Cycle matrix tabs.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
