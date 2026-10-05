# 05: Append-only audit log with refused-request logging

**Spec:** [Annual VA spec](../spec.md), stories 84 (part).
**Release:** 1
**What to build:** Every sign-in, sign-out, refused request and (from now on) every action writes an audit entry that nobody can change or delete; a VA POC can read the log in the app.
**Blocked by:** 04 (Roles held in the app and every endpoint denied by default)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 7.0 developer days across stories 84 (7.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] Audit entries hold actor, role, action, outcome, System, VA Submission, Finding id, correlation id and an allow-listed detail; an attempt to update or delete an entry through the application's database connection fails, and a test proves it.
- [ ] Entries are written in the caller's transaction; refused requests (401, 403, CSRF, step-up) are recorded even when the main transaction rolls back.
- [ ] Sign-in, sign-out, failed sign-in and every refused request produce entries; no entry ever contains a CVE id, rule name, host, comment or justification text (a sentinel test proves it).
- [ ] `GET /api/audit-entries` (VA POC only) pages and filters by actor, action, System, VA Submission and date; the front end audit log viewer shows it with no Finding detail.
- [ ] Audit entries are also emitted as ECS events on a separate audit logger.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
