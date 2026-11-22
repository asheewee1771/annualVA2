# Scheduled jobs coordinate through a small database lock, not ShedLock

Status: accepted

Reminders, the overdue sweep, cycle close, exemption lapse, scan polling and the mail outbox run on a schedule and must not run twice when more than one instance is up. Rather than add ShedLock, jobs claim work with `SELECT ... FOR UPDATE SKIP LOCKED` and a `scheduler_lock` row with a `locked_until` lease. (The scan job went further: it holds no row lock across its scanner and store calls and guards each write by the status and attempts it read, ADR 0021 decision 3.) Jobs are plain beans driven by the injected `Clock`; `@Scheduled` triggers them only when `annualva.scheduling.enabled` is true, so Seam 1 tests move the clock and call the job directly.

Consequences: one less dependency and one small table; every job must be idempotent (dedupe keys on notifications, idempotent flag setting); an architecture test requires scheduled methods to live in classes named `*Job`. The switch is on the scheduler itself (`app.SchedulingConfig`, which enables scheduling only when `annualva.scheduling.enabled` is true), not on each job (ticket 67): the `*Job` beans exist in every profile and nothing calls them while scheduling is off, so a Seam 1 test runs a job's own bean, the path the scheduler takes in production.
