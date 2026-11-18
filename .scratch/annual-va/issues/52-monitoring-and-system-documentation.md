# 52: Key-signal monitoring and system documentation

**Spec:** [Annual VA spec](../spec.md), stories lm-16, pm-6.
**Release:** 1
**What to build:** Operators can see the signals that matter (overdue submissions, scan failures and timeouts, mail failures, step-up refusals, audit-write failures) and the system is documented for hand-over.
**Blocked by:** 51 (Production adapters: S3 with SSE-KMS, SFS scanner, MCNS mail, MCC service client)
**Status:** ready-for-agent

- [x] Meters for the listed signals are registered and exposed on an authenticated `/actuator/prometheus`; `/actuator/health` stays anonymous and liveness-only; a Seam 1 test reads each meter after the triggering action.
- [x] The README gains a runbook section with the alert threshold and the operator response for each signal, and a profiles and secrets section for SIT, UAT and production.
- [ ] `/tech-arch-doc` produces the technical architecture document under `docs/`; a doc review confirms it names every module, boundary adapter, profile and ADR.

## Comments

### Progress note (2026-11-18): the repository's part is built (ADR 0060)

Built on `impl/52-monitoring-and-system-documentation`. Ticket 51 is open beyond the file store, but nothing here needs the other adapters, so this ticket went ahead.

- **Box 1 (ticked):** `monitoring.KeySignals` registers the Overdue gauges (`kind` submission and not_started), `annualva_scan_failures_total`, `annualva_scan_timeouts_total`, `annualva_mail_failures_total{kind}` (send_failed, abandoned, record_failed, refused, address_rejected), `annualva_stepup_refusals_total{kind}` (required, failed) and `annualva_audit_write_failures_total`. `/actuator/prometheus` has its own chain with a bearer token (`ANNUALVA_MONITORING_SCRAPE_TOKEN`, a secret with no default); `/actuator/health` is anonymous and answers only `{"status":"UP"}`. Seam 1 reads each meter after the triggering action (`MetricsEndpointTest`, `OverdueSignalTest`, `ScanSignalTest`, `RegisterScanSignalTest`, `MailSignalTest`, `StepUpSignalTest`, `AuditFailureSignalTest`).
- **Box 2 (ticked):** README "Monitoring and run book" (threshold and operator response for each signal) and "Profiles, secrets and environments (SIT, UAT, production)".
- **Box 3 (open):** `docs/tech-architecture.md` and `.html` exist and name every module, boundary port and adapter, profile, ADR and table, enforced by `DocumentationCompletenessTest`. They were assembled by script from the code, the migrations (applied to a scratch PostgreSQL) and the ADRs, following the `/tech-arch-doc` layout, and the vulnerability section has `npm audit` only: the Maven scan (OWASP dependency-check) was not run for want of the NVD data. A person should read the prose and run the back-end scan before this box is ticked.

Human Decision Needed (all provisional in ADR 0060): the scraper's credential (bearer token chosen; mutual TLS or a platform service identity is the alternative); the alert thresholds in the run book, which are starting values until ticket 55 sets the non-functional targets and picks the monitoring product; where the token is kept per environment.
