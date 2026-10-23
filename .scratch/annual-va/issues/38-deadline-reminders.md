# 38: Deadline reminders for SMOs, Cyber Managers and VA POCs

**Spec:** [Annual VA spec](../spec.md), stories 88, 89, 90.
**Release:** 1
**What to build:** SMOs get reminders 5 days before and on the end of week 2, then weekly while Overdue with the Cyber Manager copied; Cyber Managers get the same pattern against week 4 with the VA POCs copied; VA POCs get reminders 2 weeks and 1 week before the Quarter's last day.
**Blocked by:** 37 (Email through the mail relay with the content rules), 26 (Overdue is a flag: soft windows, nothing locks)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 13.0 developer days across stories 88 (10.0), 89 (2.0), 90 (1.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] A daily planner (08:00 Singapore) computes due reminders from each submission's windows and Overdue state and creates notifications with a dedupe key so a rerun never sends twice; copies go as separate messages since the relay has no cc.
- [ ] Seam 1 tests set the clock to each reminder day and run the planner, asserting exactly the expected messages and recipients, and none for Quarters handled outside the app, Withdrawn submissions or final states.
- [ ] Before Release 2 gives an added System its own windows, a System added to the Register after its Quarter's Submission Window is flagged Not Started Overdue by the next sweep (ADR 0033 item 3), so its SMO gets the Overdue reminder at once; this is intended, and `va_system.added_on` is not used to hold it back (reviewer decisions, 2026-10-23).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
