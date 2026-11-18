# The key signals are counted in one place and scraped with a bearer token, and health says only that the process is up

Status: accepted for the repository's part of ticket 52 (IM8 lm-16, pm-6). Provisional where it stands in for a decision only a person can make: items 2 and 6, listed under "Human Decision Needed". The products and the numbers wait for ticket 55.

Ticket 52 builds what the repository can build: the meters, the endpoint that exposes them, the run book that says what to do about each, and the architecture document. It does not choose a monitoring product, an alert manager or any target (availability, response time, recovery): those are ticket 55 (H3 in `docs/implementation-plan.md`).

1. **One class names every signal: `monitoring.KeySignals`.** A new leaf module, `sg.annualva.monitoring`, depends on no other module (`ArchitectureTest`, `ModuleConventionsArchTest`), so the modules that act can record a signal without a new dependency between them. Every counter is registered when the application starts, so a scrape shows it at zero before the first event. A name is a constant; a tag is a value of a closed enum (`kind`), never text from a request, a report or a person, and no meter carries an id (lm-19, the same rule as the audit log and the logs). The signals:

   | Meter (Prometheus name) | Kind | Counted where |
   |---|---|---|
   | `annualva_submissions_overdue{kind="submission"}` | gauge | VA Submissions flagged Overdue and not finished (`SubmissionStore`, read at each scrape) |
   | `annualva_submissions_overdue{kind="not_started"}` | gauge | Systems flagged Overdue with no VA Submission in that Cycle, not retired |
   | `annualva_scan_failures_total` | counter | a scanner or store call that failed, at each call (`ScanPoller`, `MalwareScan`) |
   | `annualva_scan_timeouts_total` | counter | a file or workbook given up on after its third scan attempt |
   | `annualva_mail_failures_total{kind=...}` | counter | `send_failed` (each failed hand-over), `abandoned` (given up on), `record_failed` (the relay answered and the answer could not be written: the mail may go twice), `refused` (a value broke the allow-list), `address_rejected` (the relay refused the address: SMO Unreachable) (`MailOutbox`) |
   | `annualva_stepup_refusals_total{kind=...}` | counter | `required` (a sensitive action with no fresh sign-in), `failed` (a step-up round trip that did not prove one) (`AccessAudit`) |
   | `annualva_audit_write_failures_total` | counter | an audit entry the database refused (`AuditLog`); the exception still propagates, so the action that the entry described rolls back |

   The JVM, HTTP-server and datasource meters Spring Boot registers stay as they are.

2. **The scrape is a bearer token on its own security chain** (provisional). The ticket says "authenticated `/actuator/prometheus`". A person's MCC SSO session cannot authenticate a scraper, and anonymous access limited by the network is not "authenticated", so `app.MetricsScrapeSecurity` is a second `SecurityFilterChain`, ahead of the application's, matching that one path: stateless (no session, no cookie), one credential, `Authorization: Bearer <annualva.monitoring.scrape-token>`, compared as two SHA-256 digests with `MessageDigest.isEqual`. A wrong or missing token is a 401 audited like any refused request (lm-4). The token is a secret with no default (ADR 0005, like the database password): 32 to 200 visible characters, checked at start, set only from `ANNUALVA_MONITORING_SCRAPE_TOKEN` (a mock under `dev-mcc` and `dev-local`, a test value under `ci`, a placeholder in `.env.sit.example`), and left out of `toString()`. An environment that does not set it does not start, so no deployment exposes the metrics open or forgets to scrape them. No session of a signed-in person opens it, and the token opens nothing else: `management.endpoints.web.exposure.include` is `health,info,prometheus`, and the application's chain keeps every other `/actuator` path behind a session. Alternatives: a role on the existing chain (no way for a scraper to sign in; rejected), HTTP Basic (a second secret shape for no gain), mutual TLS at the ingress (stronger, and the deployment's to add on top; the token is what the repository can prove).

3. **Health is liveness only.** `management.health.defaults.enabled=false` and `show-details`/`show-components` are `never`, so `GET /actuator/health` answers `{"status":"UP"}` for the process and nothing about the database, the disk or any boundary. A database or scanner outage is a signal for the run book, not a reason for the platform to restart a healthy instance, and an anonymous caller learns nothing about the dependencies. `/actuator/info` stays anonymous (it exposes nothing; no contributor is configured).

4. **The Overdue gauges read the stored flag.** They run two `count(*)` statements per scrape (static SQL in `SubmissionStore`), so the value is as fresh as the sweep (hourly, ADR 0033) and the scrape. A count that cannot be read is NaN with a fixed log line (the kind and the exception's class, no message), a gap in the series and not a zero. The alert is on how long the gauge stays above zero, not on a rate.

5. **What a counter does not see** (so the run book does not claim more than the code does):
   - An audit entry whose transaction fails at commit (a deferred constraint, a lost connection) is not an insert that failed, so it is not counted; the action rolls back all the same.
   - The sign-in machinery and the refusal listener swallow a failed audit write after the count (`AccessAudit` logs `audit-write-failed`): the person is still refused, and the counter is the only other trace.
   - The System Register upload's scan (`MalwareScan.scanNow`) counts into the same two signals as a Scan Report file's (`RegisterScanSignalTest`), so a scanner outage shows whichever path met it first. A scanner that answers `ERROR` is a failure at each answer; one that never finishes is a timeout and no failure.

6. **Thresholds are starting values** (provisional). The run book's thresholds in `README.md` (any audit write failure; mail send failures sustained over 15 minutes; an Overdue count above zero for a day; and so on) are chosen from how each meter behaves, not from an availability or response-time target, because none exists until ticket 55. They are the first values for the monitoring product to be loaded with, and they change by editing the run book.

7. **The architecture document is generated and checked.** `docs/tech-architecture.md` (and `.html`) is produced by `/tech-arch-doc` and is a point-in-time document. `DocumentationCompletenessTest` (in the architecture test package, no HTTP) fails the build when the document does not name every module package, boundary adapter class, Spring profile and ADR in the repository, so a new module, adapter, profile or ADR cannot be merged without the document being refreshed. It checks that names are present, not that the prose is right: a person reads the prose at the doc review.

Not done here, on purpose:
- A monitoring product, dashboards, alert rules in a product's syntax, a pager or an on-call rota (ticket 55; the run book gives the content).
- Request-rate and latency alerts: the server's `http_server_requests` meters exist, but without a target there is nothing to alert on.
- A distributed trace or log shipping; lm-15's ECS logs are unchanged.
- Alerting on the sweep or a scheduled job not running at all (no heartbeat meter): the Overdue gauge staying flat after a window has passed is the only hint today.

Human Decision Needed:
- Item 2: the scraper's credential is a bearer token (chosen). Option B: mutual TLS or a service identity from the platform, if the hosting tier provides one.
- Item 6: the alert thresholds, after ticket 55 sets the non-functional targets and the monitoring product is known.
- The monitoring product and where the token is kept (ticket 55, H3).
