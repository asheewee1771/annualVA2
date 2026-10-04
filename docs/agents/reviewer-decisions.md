# Reviewer decisions

Human decisions on reviewer findings. Check here before asking again. Each entry: Area, Finding, Chosen action, When to reuse.

## Java 21 baseline

- **Area:** build and runtime (`java.version`, developer JDKs, CI and hosting)
- **Finding:** the org standards assume Spring Boot 4.x on Java 21 or 25; only JDK 21 is installed here, and the bootstrap standard that pinned Java 25 was retired in September 2026.
- **Chosen action:** Java 21 (LTS) with Spring Boot 4.0.x. JDK 17 on the machine is not used. Decided 2026-10-04 at scaffold.
- **When to reuse:** any finding about the Java version, toolchains or the hosting runtime. Revisit only if the chosen hosting mandates another LTS.

## No front-end unit tests or coverage gate

- **Area:** front-end testing (`frontend/`), the build gate's Vitest and coverage step
- **Finding:** the build gate asks for Vitest with coverage of at least 80 %, but the spec's Testing Decisions rule out a seam for React components: business rules are tested at the HTTP API (Seam 1) and the browser only through thin Playwright scenarios (Seam 2).
- **Chosen action:** keep the spec. No Vitest, no front-end coverage gate. Prettier, `tsc -b`, `vite build`, the no-raw-HTML gate, dependency-cruiser and fallow still apply. The back-end coverage gate applies in full. Decided 2026-10-04 at scaffold (ADR 0009).
- **When to reuse:** every ticket with front-end changes when a gate flags missing front-end tests or coverage.

## Business time zone

- **Area:** VA Cycle, Quarter and Window arithmetic, deadlines, reminders
- **Finding:** dates computed in UTC are a day behind for Singapore users from midnight to 08:00 on boundary days.
- **Chosen action:** compute business dates in `Asia/Singapore` (`annualva.time-zone`); store timestamps in UTC; read time only through the `Clock` bean. Decided 2026-10-04 (ADR 0006).
- **When to reuse:** every ticket that computes a Quarter, Window or due date.

## Semgrep runner

- **Area:** the semgrep gate
- **Finding:** the Semgrep CLI is not installed on the developer machine.
- **Chosen action:** run it from the official `semgrep/semgrep` Docker image, mounting the repo.
- **When to reuse:** every semgrep gate run on this machine.

## mock-m-sso is the local platform

- **Area:** local development stack (`compose.yml`, `application-dev-mcc.yml`)
- **Finding:** the MCC mock stack has no recorded git URL and is not on this machine; the user chose it from day one anyway.
- **Chosen action:** compose and the `dev-mcc` profile target `../mock-m-sso` as the org bootstrap standard prescribes (service blocks copied verbatim). Seam 1 tests never need it. Obtaining it is a ready-for-human ticket; no interim OIDC stand-in is built unless that ticket slips past the sign-in slice. Decided 2026-10-04 (ADR 0003).
- **When to reuse:** any finding that the local stack is missing or that a mock block should be "modernised".

## Step-up is a fresh OIDC sign-in

- **Area:** sensitive actions (ac-2 step-up list)
- **Finding:** the org SSO standards specify no step-up mechanism; the spec assumes MCC SSO supports it or the app forces a full re-login.
- **Chosen action:** step-up is a fresh authorization-code round trip (`prompt=login`, `max_age=0`) with a server-side `auth_time` freshness check that fails closed when `auth_time` is missing. If MCC SSO ignores `prompt`, the same component forces a full re-login. App-level OTP via MCNS is the documented fallback only. Decided 2026-10-04 (ADR 0004).
- **When to reuse:** every step-up finding, and the SIT verification of MCC SSO behaviour.

## IM8 controls owned by a later ticket

- **Area:** the im8 gate and any catalogue-level compliance check
- **Finding:** controls not yet built are marked FAIL even when the plan assigns them to a later ticket; under a strict reading every ticket fails until the last one lands.
- **Chosen action:** a control that fails only because a later ticket owns it does not block the current ticket, provided the finding names the owning ticket and the change does not weaken the control. It stays FAIL in the report as a tracked deferral. A control the current ticket owns, or weakens, still blocks. Decided 2026-10-04.
- **When to reuse:** every gate run; the owning ticket's own gate must then show the control passing.
