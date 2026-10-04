# Scheduled jobs coordinate through a small database lock, not ShedLock

Status: accepted

Reminders, the overdue sweep, cycle close, exemption lapse, scan polling and the mail outbox run on a schedule and must not run twice when more than one instance is up. Rather than add ShedLock, jobs claim work with `SELECT ... FOR UPDATE SKIP LOCKED` and a `scheduler_lock` row with a `locked_until` lease. Jobs are plain beans driven by the injected `Clock`; `@Scheduled` triggers them only when `annualva.scheduling.enabled` is true, so Seam 1 tests move the clock and call the job directly.

Consequences: one less dependency and one small table; every job must be idempotent (dedupe keys on notifications, idempotent flag setting); an architecture test requires scheduled methods to live in classes named `*Job`.
