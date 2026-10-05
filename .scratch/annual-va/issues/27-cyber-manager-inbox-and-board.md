# 27: Cyber Manager inbox and Quarter board

**Spec:** [Annual VA spec](../spec.md), stories 48, 49.
**Release:** 1
**What to build:** A Cyber Manager sees the VA Submissions for the Systems assigned to them, Overdue first, and a Quarter board with one column per state.
**Blocked by:** 26 (Overdue is a flag: soft windows, nothing locks)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 10.0 developer days across stories 48 (5.0), 49 (5.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/dashboards/cm-inbox?cycle=` returns the submissions of the Systems assigned to the caller in the Register, ordered Overdue first then by due date, with state, counts of confirmed and pending Findings and a blocked marker where segregation of duties will apply.
- [ ] `GET /api/dashboards/quarter-board?cycle=&quarter=` returns the Systems of the Quarter grouped by state (Not Started through Endorsed, Exempted, Withdrawn); a Cyber Manager sees only Systems assigned to them in the Register.
- [ ] An SMO calling the Cyber Manager inbox gets 403; the front end shows the Inbox and Quarter board tabs.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
