# 02: Walking skeleton: repository, security baseline and Seam 1 harness

**Spec:** [Annual VA spec](../spec.md), stories none (foundation).
**Release:** 1
**What to build:** A developer can clone the repo, run both quality gates green, start the back end against the compose PostgreSQL and open the front end shell with the classification banner. The skeleton carries the cross-cutting IM8 controls every later slice relies on.
**Blocked by:** None (can start immediately)
**Status:** closed

- [x] Monorepo layout (`backend/`, `frontend/`, `scripts/`, `compose.yml`) with the mock-m-sso service blocks copied verbatim plus a project PostgreSQL, LocalStack init scripts for the KMS key and SSE-KMS buckets, `AGENTS.md`, `README.md`, `CONTEXT.md`, ADRs and the tracker folder.
- [x] Spring Boot 4.0.x on Java 21 with JdbcClient and Flyway; every endpoint is denied by default, CSRF uses the synchronizer token bootstrapped from `GET /api/csrf`, errors are RFC 9457 problems carrying the correlation id and no internal detail, and the IM8 header set (CSP with `frame-ancestors 'none'`, HSTS one year, nosniff, frame deny, referrer, permissions, COOP) is on every response.
- [x] Logs are ECS JSON with the correlation id in `trace.id`; virtual threads are on; time is read only through the `Clock` bean.
- [x] The Seam 1 harness runs the full application on a random port with a Testcontainers PostgreSQL and a controllable clock; ArchUnit enforces module dependencies, guarded controllers, the Clock-only time rule and no fakes on main; JaCoCo gates line and branch coverage at 80 %.
- [x] The front end shell shows the classification banner and the signed-out screen, with Prettier, `tsc -b`, `vite build`, the no-raw-HTML gate, dependency-cruiser and fallow all green.
- [x] The tracker is seeded: spec, structured stories, dependency DAG and dev report, and this ticket set.

## Comments

### Verification (2026-10-05, scaffold session)

Done in commits ea72f69 through 272ee0d. `./mvnw -B -ntp clean verify`: 22 Seam 1 and architecture tests green, JaCoCo line and branch coverage met. `npm run check`: every front-end gate green. `docker compose up -d postgres localstack` created `alias/annualva-dev` and the two SSE-KMS buckets. The back end under `dev-mcc` reaches the database connection step (Docker was down at the time of the last check), so the missing mock-m-sso certificate path does not block startup. Boundary fakes beyond the clock arrive with the slice that introduces each adapter.
