# 67: Operators are alerted when a scheduled job stops running

**Spec:** [Annual VA spec](../spec.md), story lm-16 (follow-up raised by the ticket 52 review).
**Release:** 1
**What to build:** Ticket 52 counts the key signals (Overdue, scan failures and timeouts, mail failures, step-up refusals, audit-write failures) and gives the run book an alert for each. A scheduled job that silently stops, because its thread died, a lock was never released or the application started without the scheduler, raises none of them: the counters simply stop moving, which looks the same as a quiet day. Give each scheduled job a "last completed run" signal, so an operator is alerted when a job has not completed within its expected interval.
**Blocked by:** 52 (Key-signal monitoring and system documentation)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** not estimated; raised by the ticket 52 review of 2026-11-22. Small: one gauge per job, the run book and Seam 1 tests.

- [ ] Every scheduled job the application runs (at least the Overdue sweep, the deadline reminders, the mail outbox and the scan poller; the list is read from the code, not typed by hand) records when it last completed a run, and the time is exposed on the authenticated `/actuator/prometheus` as one series per job, tagged by a closed set of job names (ADR 0060).
- [ ] A run that fails does not move its "last completed" time; a run that does nothing because there was nothing to do still does.
- [ ] Time is read only through the `Clock` bean; no report content, person or record id appears in a tag.
- [ ] The README run book gains, for each job, its expected interval, the alert threshold ("not completed for N times its interval") and the operator response. The thresholds are starting values, provisional until ticket 55, like ticket 52's.
- [ ] A Seam 1 test reads each job's series after the job runs, proves a failed run leaves it unchanged, and fails when a scheduled job exists that has no series.
- [ ] ADR 0060 records the signal. The back-end gate passes; the front-end gate applies only if the front end changes.

## Comments

**2026-11-22, raised and approved:** the ticket 52 IM8 review (finding F2, lm-16) found that no signal shows a scheduled job that has stopped. It is new monitoring scope, so the user chose a follow-up ticket over widening ticket 52, and asked for it to be built with the remaining tickets, so it is `ready-for-agent`. It is one vertical slice that can be shown alone once ticket 52 has landed.
