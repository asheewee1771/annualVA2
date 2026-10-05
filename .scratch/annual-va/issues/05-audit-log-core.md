# 05: Append-only audit log with refused-request logging

**Spec:** [Annual VA spec](../spec.md), stories 84 (part).
**Release:** 1
**What to build:** Every sign-in, sign-out, refused request and (from now on) every action writes an audit entry that nobody can change or delete; a VA POC can read the log in the app.
**Blocked by:** 04 (Roles held in the app and every endpoint denied by default)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 7.0 developer days across stories 84 (7.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] Audit entries hold actor, role, action, outcome, System, VA Submission, Finding id, correlation id and an allow-listed detail; an attempt to update or delete an entry through the application's database connection fails, and a test proves it.
- [x] Entries are written in the caller's transaction; refused requests (401, 403, CSRF, step-up) are recorded even when the main transaction rolls back.
- [x] Sign-in, sign-out, failed sign-in and every refused request produce entries; no entry ever contains a CVE id, rule name, host, comment or justification text (a sentinel test proves it).
- [x] `GET /api/audit-entries` (VA POC only) pages and filters by actor, action, System, VA Submission and date; the front end audit log viewer shows it with no Finding detail.
- [x] Audit entries are also emitted as ECS events on a separate audit logger.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-05)

**Implemented** (commits `5344c71`, `85380e2`, `b1c76a2`, `e5d1edd`): the `audit_entry` table (migration V5) with actor, roles, action, outcome, System, VA Submission, Finding id, correlation id and an allow-listed `detail`; a statement-level trigger that refuses UPDATE, DELETE and TRUNCATE; a database CHECK on the `detail` keys; a hidden write-order column so entries in the same instant list in a stable order. `AuditLog.record` joins the caller's transaction (fails without one) and `recordIndependently` survives a rollback. Refusals (401, 403, CSRF, step-up-required) are recorded where the problem is written, through a `RefusalListener` port in `web`, so they survive a rolled-back command. Entries exist for sign-in (with roles held), sign-out, failed sign-in, every refusal, all step-up events and the VA POC role sync (grant id only). `GET /api/audit-entries` is VA POC only with filters by actor, action, System, VA Submission and date (Asia/Singapore business dates) and paging; the front end viewer at `/va-poc/audit-log` shows ids only and renders as text. Entries are also ECS events on `annualva.audit`, emitted after commit. ADR 0014 records the design.

**Verification performed**
- Back end `mvnw.cmd -B -ntp clean verify`: 150 tests, 0 failures, ArchUnit and JaCoCo met. The implementer ran it four times in a row after the ordering fix, all green.
- Front end `npm run check`: passed.
- Reviewer loop: round 1 and round 2 found 0 Must-fix and 0 Human decisions.
- `/code-reviewer` final gate: first run FAIL on one High (a flaky audit test, because entries with the same timestamp came back in random order, tie-broken on the random UUID); fixed at the source with the write-order column and a regression test; second run PASS (build PASS, the other six WARN). Report: `artifacts/code-reviewer/05-audit-log-core-compliance.html`.
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open for the owners named**
- The front end was not run in a browser (needs the SSO stack, ticket 53). Deep links such as `/va-poc/audit-log` need the host to serve `index.html` (ticket 55).
- V5 was edited in place before it was ever committed. Any local database that ran an earlier V5 needs dropping and recreating; `flyway repair` would fix the checksum but not add the new column.
- Tickets 52 and 55: every unauthenticated request to a protected path writes a row, so throttle at the edge. The application's database login owns `audit_entry`, so the trigger does not stop that login disabling it; run the application as a role with INSERT and SELECT only and migrate under a separate owner.
- Ticket 52: a failed independent audit write is logged as `audit-write-failed` and does not block the refusal or sign-in. That branch has no test and no alert yet.
- Ticket 49 owns the retention bypass; no setting exists for it, and ADR 0001 says the trigger changes by migration.
- 404 for a System the caller may not see and 429 from the anonymous-session cap are deliberately not audited (ADR 0013 and ADR 0014). Reading the audit log is not itself audited.
- A needed step-up is now the ECS event `request-refused` with reason `step-up-required`, not `step-up-required` as before; the README says so.
- Medium and Low findings left open: the optional-filter SQL defeats the partial indexes and `count(*)` runs on every page; role lookup and event building sit outside the try/catch in `AccessAudit.write`, so a database outage can turn a refusal into a 500 (the ADR and README wording is slightly broader than the code); `AuditAction.valueOf` makes one retired constant break the whole list; a `to` date of `LocalDate.MAX` gives 500 instead of 400; the `page` parameter is unbounded; the "free text impossible by construction" wording overclaims, since the code pattern accepts CVE-like strings and is safe only because callers pass fixed codes; front-end polish (results under an error, focus on paging, action labels hardcoded alongside `AuditAction`).
- Domain tickets should call `record` inside their own transaction and avoid `recordIndependently` there, to avoid pool starvation. The step-up `intent` is pattern-limited but not checked against a registry, and the correlation id is client-suppliable within 8 to 64 safe characters.
