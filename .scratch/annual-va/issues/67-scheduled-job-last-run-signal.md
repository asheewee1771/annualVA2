# 67: Operators are alerted when a scheduled job stops running

**Spec:** [Annual VA spec](../spec.md), story lm-16 (follow-up raised by the ticket 52 review).
**Release:** 1
**What to build:** Ticket 52 counts the key signals (Overdue, scan failures and timeouts, mail failures, step-up refusals, audit-write failures) and gives the run book an alert for each. A scheduled job that silently stops, because its thread died, a lock was never released or the application started without the scheduler, raises none of them: the counters simply stop moving, which looks the same as a quiet day. Give each scheduled job a "last completed run" signal, so an operator is alerted when a job has not completed within its expected interval.
**Blocked by:** 52 (Key-signal monitoring and system documentation)
**Status:** closed
**Effort (orchestrator estimate):** not estimated; raised by the ticket 52 review of 2026-11-22. Small: one gauge per job, the run book and Seam 1 tests.

- [x] Every scheduled job the application runs (at least the Overdue sweep, the deadline reminders, the mail outbox and the scan poller; the list is read from the code, not typed by hand) records when it last completed a run, and the time is exposed on the authenticated `/actuator/prometheus` as one series per job, tagged by a closed set of job names (ADR 0060).
- [x] A run that fails does not move its "last completed" time; a run that does nothing because there was nothing to do still does.
- [x] Time is read only through the `Clock` bean; no report content, person or record id appears in a tag.
- [x] The README run book gains, for each job, its expected interval, the alert threshold ("not completed for N times its interval") and the operator response. The thresholds are starting values, provisional until ticket 55, like ticket 52's.
- [x] A Seam 1 test reads each job's series after the job runs, proves a failed run leaves it unchanged, and fails when a scheduled job exists that has no series.
- [x] ADR 0060 records the signal. The back-end gate passes; the front-end gate applies only if the front end changes.

## Comments

**2026-11-22, raised and approved:** the ticket 52 IM8 review (finding F2, lm-16) found that no signal shows a scheduled job that has stopped. It is new monitoring scope, so the user chose a follow-up ticket over widening ticket 52, and asked for it to be built with the remaining tickets, so it is `ready-for-agent`. It is one vertical slice that can be shown alone once ticket 52 has landed.

### Verification (2026-11-23)

**What was built.** Every `@Scheduled` method has a series `annualva_job_last_completed_seconds{kind}` on the authenticated `/actuator/prometheus`, ten in all: `scan_poller`, `mail_dispatch`, `export_worker`, `overdue_sweep`, `deadline_reminders`, `cycle_lifecycle`, `exemption_lapse`, `role_lapse`, `retention_execute`, `retention_list`.
- **Tags and time.** The tag is `kind`, from the closed enum `KeySignals.Job`; Prometheus adds its own `job` label. The time is read from the `Clock` bean as the job's last step, after its work commits.
- **What counts as completed.** A run that throws does not move the series. A run whose pass had work and none of it went through does not move it either; `monitoring.PassTally` counts this for the scan job, reminders and exports, and the disposal executor answers it inline. A run with nothing to do does move it. Mail is the exception: every mail failure is already counted.
- **Starting value.** Before a job's first run, its series holds the application's start time, so an instance started without the scheduler still alerts.
- **Where scheduling is switched.** Scheduling is switched on in one place (`app.SchedulingConfig`, `annualva.scheduling.enabled`). The job beans are in every test context and never fire there, and Seam 1 runs the real beans through one test-only runner (`ScheduledJobRuns`).
- **Run book.** The README gives each job its interval, a starting alert threshold (a multiple of the interval, provisional until ticket 55, like ticket 52's) and the operator response. It also adds a restart-count alert and a note that, with virtual threads on, all fixed-delay jobs share one scheduler thread.
- **ADRs.** ADR 0060 item 8 records the signal; ADRs 0012, 0033, 0044 and 0052 are updated.
- **Commits.** `9184ee3` (code and tests), `2a5fac1` (ADRs, README, architecture document), `0347760` (report), merged by `8d85f46`.

**How it was verified.**
- Seam 1:
  - `ScheduledJobSignalTest` (4 tests) runs every discovered job and checks each moves a series of its own to the clock's time. It fails when a job has no series, proves a failed Overdue sweep and a failed export listing leave the time unchanged, and asserts the scheduler is off in tests.
  - Failure cases in `ScanVerdictTest`, `DeadlineReminderTest`, `ExportLifecycleTest` and `DisposalSupersededTest`: every file failed, a refused discard, every reminder failed, every removal refused, a failed listing, a failed disposal.
  - Every disposal test's listing now proves it completed.
  - Mutants of each recording rule were caught.
- Back-end gate on the final tree (identical to the merged tree on `main`): `./mvnw -B -ntp clean verify` passed. 1,433 tests, 0 failures, 1 skipped; line coverage 97.98 %, branch 91.72 %; BUILD SUCCESS. `refresh-tech-arch-doc.py --check` is clean. Front-end gate: not applicable.
- Review:
  - The do-work reviewer's one must-fix was fixed and confirmed.
  - `/code-reviewer`: PASS, no Critical, High or Medium open (`artifacts/code-reviewer/67-scheduled-job-last-run-signal-compliance.html`). Four rounds of code-review and thermo-nuclear, with three correction passes; their Mediums were fixed. Springboot, im8, semgrep and react did not see the last passes; the report says which.

**Not covered.** The alert rules themselves live in the monitoring product, not in this repository; the thresholds wait on ticket 55. Whether a deadline reminder's per-item failure deserves its own counter is left open (im8 note).

All six boxes are ticked.
