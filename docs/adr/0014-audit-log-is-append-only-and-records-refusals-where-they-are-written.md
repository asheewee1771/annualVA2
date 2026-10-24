# The audit log is append-only in the database, and refusals are recorded where the 401 or 403 is written

Status: accepted

IM8 lm-4 and story 84 want every sign-in, sign-out, failed sign-in and refused request on record, with entries nobody can change, and a refused request must be recorded even when the command it refused had rolled back. Three decisions follow.

1. **Append-only by trigger.** `audit_entry` has a statement-level trigger that refuses `UPDATE`, `DELETE` and `TRUNCATE` for every connection, including the application's. There is no setting that lifts it. The retention ticket replaces the trigger by a migration when disposal exists (ADR 0001: the trigger changes deliberately, never by accident). The application's database user owns the table, so the trigger guards against application bugs and mistakes, not against someone holding that login; hosting should run the application as a role with `INSERT` and `SELECT` only (ticket 55).
2. **Two ways to write.** `AuditLog.record` joins the caller's transaction and fails when there is none, so an entry commits and rolls back with the action it describes. `AuditLog.recordIndependently` runs in its own transaction (`REQUIRES_NEW`) and commits whatever the caller does later. Sign-in, sign-out, step-up and refusals use the second; domain commands use the first. The ECS event for an entry is emitted after its transaction commits, so a rolled-back entry is never announced.
3. **Refusals are recorded at the response writers.** The security entry point, the access-denied handler (CSRF) and the controller advice (403 `forbidden`, `step-up-required`) report each refusal to a `RefusalListener` port in `web`, which the access module implements because it knows the caller. The advice runs after the command's transaction has ended, so the refusal outlives a rollback without a nested transaction. A 404 for a System the caller may not see (ADR 0013) and a 429 from the anonymous-session cap are not recorded: the first must stay indistinguishable from a missing resource, and the second would turn a flood into a flood of rows.

Entries carry ids and fixed codes only. `detail` keys are an allow-list in `DetailKey` and in a CHECK constraint, and a value must be a short code, an id or a number, so free text and report content cannot reach an entry by construction (lm-19).

Consequences: every unauthenticated request to a protected path writes a row, so throttling at the edge matters (tickets 52 and 55); a failed independent write is logged as `audit-write-failed` (exception type only) and does not block the refusal or the sign-in; a new detail key needs a migration as well as an enum constant.

Amended by ADR 0048: the trigger lets a DELETE through only inside the executor's transaction for a disposal list a VA POC approved, and only for entries before that list's cutoff; UPDATE and TRUNCATE are still refused for every connection.
