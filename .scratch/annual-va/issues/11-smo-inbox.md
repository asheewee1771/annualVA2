# 11: SMO inbox: my Systems and what to do by when

**Spec:** [Annual VA spec](../spec.md), stories 18, 71 (Not Started).
**Release:** 1
**What to build:** An SMO/Tech Lead signs in and sees their Systems for the current Quarter with the state (Not Started for now), the due dates and what is needed next.
**Blocked by:** 10 (Apply a Register preview; SMO and Cyber Manager roles follow the Register)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 8.0 developer days across stories 18 (4.0*), 71 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/dashboards/smo-inbox?cycle=` returns, for the caller's Systems in the selected Cycle, the Quarter, state (Not Started when no VA Submission exists), submit, review and endorse due dates, and the next action; Systems in Quarters handled outside the app are listed under a separate heading with no action.
- [ ] A Cyber Manager or VA POC calling the SMO inbox gets 403; an SMO sees nothing of other people's Systems.
- [ ] The inbox screen groups Needs your action, Waiting on others and Done, shows Overdue pills when the flag exists, and has a Start button per Not Started System (wired by the next ticket).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
