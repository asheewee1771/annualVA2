# 63: A `dev-local` profile: sign in as a persona and run on local fakes

**Spec:** [Annual VA spec](../spec.md), stories none (developer tooling; follow-up raised by ticket 01 being blocked).
**Release:** 1
**What to build:** A developer without the `mock-m-sso` stack can run the whole app on one machine with only PostgreSQL in Docker. A local page lists the six dev personas (SMO A/B, Cyber Manager A/B, VA POC 1/2) with their role and email; picking one signs the person in. The boundary adapters that ticket 51 will build for real are replaced by local fakes, so uploading, scanning and mail work too. None of this can run outside a developer's machine.
**Blocked by:** None (can start immediately)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** not estimated; decided by the user on 2026-10-27 (`docs/agents/reviewer-decisions.md`, "A `dev-local` profile signs in as a persona and fakes the boundary adapters").

- [x] A new profile `dev-local` starts the back end with only PostgreSQL running: no Keycloak, MockPass, mock-SFS, mock-MCNS or LocalStack, and no identity-provider discovery at startup. `application-dev-local.yml` binds the server to loopback only and turns the scheduled jobs on.
- [x] Safety: the application refuses to start when `dev-local` is active with `sit`, `ci` or any production profile, or when the server address is not loopback. Every class of this ticket is annotated `@Profile("dev-local")`, an ArchUnit rule fails the build if one is not, and a Seam 1 test shows that under the normal test profile `GET /api/dev/personas` and `POST /api/dev/sign-in` answer 404 and create no session. A startup warning says anyone can sign in as any persona.
- [x] `GET /api/dev/personas` lists the six personas (name, role label, email) from the existing dev identities; `POST /api/dev/sign-in` takes one persona and signs the person in through the same `SignInService` path as a real sign-in (one-session rule, role resolution from the System Register by email, the sign-in audit entry, the anonymous-session cap). Roles still come only from the Register: a persona the Register does not name sees empty screens, as in real life, and nothing creates a role grant outside the Register.
- [x] Step-up works locally: every action that asks for a fresh sign-in (ac-2) is satisfied by a local confirmation that refreshes `auth_time` for the same person and intent, and a stale session is still refused.
- [x] Local fakes under `dev-local` only, each in the adapters package: a file store on a git-ignored local folder (zones as sub-folders, so restarts keep files); a malware scanner that follows mock-SFS's keyword rule on the display name (`quarantined` blocked, `sanitized` modified, anything else unchanged) with a configurable delay; a mail relay that logs one `Event=email` line per message (System, state, action, due date, link, never an address or free text) and rejects an address whose local part ends in `.bounce`, so SMO Unreachable can be shown; an identity status that reports everyone enabled.
- [ ] Front end: a `/dev-sign-in` screen shows the personas as buttons, with a banner saying this is local development sign-in; the normal sign-in landing offers it only when `GET /api/dev/personas` answers 200. No raw HTML rendering, classification banner kept.
- [x] README: a "Running without mock-m-sso" section (start Postgres, the Register upload from `scripts/dev-data/`, `-Dspring-boot.run.profiles=dev-local`, the frontend, which persona to use for each role) and a row in the Profiles table; the unused folder is in `.gitignore`; ADR 0058 records the design and its guards.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API where a Spring context can host it (sign-in, role resolution, step-up, the 404 outside the profile) and by plain tests for the deterministic fakes; both gates pass.

## Comments

### Verification (2026-10-30)

Built on `impl/63-dev-local`, fast-forwarded into `main` at `2af8c3b` (not pushed). Both gates pass on the final tree: back end `./mvnw -B -ntp clean verify` (1329 tests, 0 failures, JaCoCo met) and front end `npm run check`. Code-reviewer report: `artifacts/code-reviewer/63-dev-local-profile-compliance.html` (no Critical or High left). ADR 0058 records the design.

Run by hand on Windows with Docker 29.5.3 (through the Vite proxy at `https://localhost:5173`): six personas listed; SMO, Cyber Manager and VA POC sign-ins resolved their roles from the sample Register (`scripts/dev-data/`); an unknown persona got 400; `report_quarantined.xml` ended `REJECTED_MALWARE` and `report_sanitized.xml` `REJECTED_SANITIZED`; after the 5-minute freshness window apply answered 403 `step-up-required`, the local step-up completed it, and a replayed confirmation answered 409.

**Not verified:** the `/dev-sign-in` and `/dev-step-up` screens have never been opened in a browser (the Browser pane refused `https://localhost:5173`), so the front-end box stays unticked until `/browser-test` has seen them. `Event=email` is covered by the plain test only.

**Human decisions (provisional, in ADR 0058):**
1. `dev-local` sets `first-live-cycle: 2026`, `first-live-quarter: 3` so a developer can start work now; a fake clock is a possible follow-up.
2. The guard also refuses a non-local database or management address, and it runs while beans are created, not before the first one.
3. The two dev paths are public in every profile so outside the profile they answer 404, not 401.
