# Annual VA

Internal web application that runs the organisation's annual vulnerability assessment (VA): the VA Cycle and its Quarters, the System Register, VA Submissions with their Scan Reports and Findings, Cyber Manager review, VA POC endorsement, Exemptions, notifications, exports, audit log and retention.

- Spring Boot 4.0 / Java 21 back end in `backend/` (PostgreSQL 17, JdbcClient + Flyway)
- Vite + React 19 + TypeScript front end in `frontend/`
- Spec: `.scratch/annual-va/spec.md`. Glossary: `CONTEXT.md`. Decisions: `docs/adr/`, `docs/agents/reviewer-decisions.md`. Agent entry point: `AGENTS.md`.

**Every credential, key and password in this repository is a mock value for local development. None is suitable for any deployed environment.**

## Prerequisites

| Tool | Version used here |
|---|---|
| Docker Desktop (Compose v2) | 29 |
| JDK | Temurin 21 (set `JAVA_HOME` to it; Maven comes from the wrapper `backend/mvnw`) |
| Node.js / npm | 24 / 11 |
| Git Bash (for `scripts/*.sh`; `openssl` ships with Git for Windows) | |
| `../mock-m-sso` | The MCC mock stack, cloned as a sibling directory `D:\projects\mock-m-sso`. It is not public: ask the App-Standards / SGTechStack owners for access. |

## Quick start

1. `docker compose up -d` (or, without `../mock-m-sso`, `docker compose up -d postgres localstack`).
2. First time only: `bash scripts/gen-dev-keys.sh` creates `backend/src/main/resources/dev-keystore.p12` and prints the base64 signing keys to paste into `application-dev-mcc.yml`.
3. Back end: `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev-mcc` then `https://localhost:8443/actuator/health`.
4. Front end: `cd frontend && npm install && npm run dev` then open `https://localhost:5173` and accept the self-signed certificate.

## Services and ports

| Service | Port | Purpose | Health |
|---|---|---|---|
| postgres | 5432 | application database (`annualva` / `annualva` / `annualva-local`) | `docker compose ps` shows healthy |
| localstack | 4566 | S3 (quarantine and clean buckets) and KMS (`alias/annualva-dev`) | `curl -sf localhost:4566/_localstack/health` |
| keycloak | 9080 (HTTPS) | MCC SSO stand-in, realm `SSO`, admin `admin`/`admin` | `curl -sk https://localhost:9080/realms/SSO/.well-known/openid-configuration` |
| mockpass | 5156 | Singpass persona picker brokered by Keycloak | `curl -s localhost:5156` |
| mock-sfs | 9083 | Secure File Service (malware scan) | `curl -s localhost:9083/actuator/health` |
| mock-mcns | 9082 | Notification Service (email) | `curl -s localhost:9082/actuator/health` |
| mock-mpds | 9081 | Person Data Service (not used by this app) | `curl -s localhost:9081/actuator/health` |
| backend | 8443 (HTTPS) | the API | `curl -sk https://localhost:8443/actuator/health` |
| frontend | 5173 (HTTPS) | Vite dev server proxying `/api`, `/oauth2`, `/login`, `/logout`, `/auth`, `/.well-known`, `/actuator` to the back end | open in a browser |

The first start of the three `maven:3` mock containers takes several minutes while Maven downloads into the sibling repo's `.m2`.

## Profiles

| Profile | Where | Use |
|---|---|---|
| (base) `application.yml` | `backend/src/main/resources` | production shape: `${ENV_VAR}` bindings only, invariant literals |
| `dev-mcc` | `backend/src/main/resources` | local development against the mock stack; mock literals |
| `ci` | `backend/src/test/resources` | Seam 1 tests: Testcontainers PostgreSQL, in-process fakes, plain HTTP |
| `sit` | `backend/src/main/resources` | first deployed target: `${VAR:dev-default}` bindings (mock-stack defaults for non-secrets, none for secrets), optional `backend/.env.sit` copied from `backend/.env.sit.example` |

Select with `SPRING_PROFILES_ACTIVE=dev-mcc` or `-Dspring-boot.run.profiles=dev-mcc`.

## Dev personas and identities

MockPass personas carry an NRIC and a uuid (the `afcas.uuid` claim); the System Register works by email. `scripts/mock-mockpass-assertions.js` adds six personas and `application-dev-mcc.yml` maps each uuid to an email:

| Persona NRIC | Email | Intended role |
|---|---|---|
| S6005040F | smo.a@dev.annualva.test | SMO/Tech Lead |
| S6005041D | smo.b@dev.annualva.test | SMO/Tech Lead |
| S6005042B | cm.a@dev.annualva.test | Cyber Manager |
| S6005043J | cm.b@dev.annualva.test | Cyber Manager |
| S6005044H | poc.1@dev.annualva.test | VA POC |
| S6005045F | poc.2@dev.annualva.test | VA POC |

Switch persona by signing out, signing in again and picking another NRIC on the MockPass page. A Register upload (`scripts/dev-data/`) assigns Systems to these emails; never name the same person as a System's SMO and Cyber Manager.

## Simulating the mocks

- **Malware verdicts (mock-SFS):** the verdict follows a keyword in the file's display name: `quarantined` is blocked, `sanitized` is allowed after sanitisation, `unchanged` or anything else is allowed unchanged. Upload `report_quarantined.xml` to see a malware rejection. `SFS_SCAN_DELAY_MS` in `compose.yml` sets the scan delay.
- **Email (mock-MCNS):** nothing is stored; read sent messages from the container log: `docker compose logs -f mock-mcns | grep "Event=email"`. A message carries only the System, state, action, due date and a link.

## Sign-in and the anonymous-session cap

Starting a sign-in (`/oauth2/authorization/**`) or bootstrapping CSRF (`/api/csrf`) without a session writes a row to the session store, so the app caps how many such sessions one client may start per minute (`annualva.sign-in.anonymous-sessions-per-minute`, default 60; requests that carry a session are not counted). Two limits to know about:

- The cap is per client address as the app sees it. Behind a single gateway or load-balancer address every user shares one counter, so it acts as a global backstop; whether to throttle per client (real client address from the edge, or at the WAF) is a hosting decision (ticket 55).
- It is only a partial IM8 as-4 mitigation. Brute-force protection of the sign-in credentials belongs to the identity provider (MCC SSO), not to this app.

Calls from the app to MCC SSO (token endpoint, JWKS) time out after `annualva.mcc.connect-timeout` (3 s) and `annualva.mcc.read-timeout` (10 s); a timeout is a refused sign-in with reason `provider-error`.

## Step-up before sensitive actions

A sensitive command calls `StepUp.require("<intent>")` first (intent: a lower-case code such as `register-apply`). It is refused with `403 step-up-required` and a `stepUpPath` unless the session's `auth_time` is within `annualva.step-up.freshness-window` (5 minutes). The SPA sends the browser to `GET /auth/step-up?intent=...&returnTo=/route`, which starts a new authorization request with `prompt=login` and `max_age=0`. The callback refuses a token whose `auth_time` is absent or older than the step-up's start (less `annualva.step-up.clock-skew`, 30 s), that lacks MFA evidence, or that belongs to another person; otherwise it only refreshes `auth_time` (never the 8-hour limit) and redirects to `returnTo?stepup=done` (or `?stepup=refused&reason=<code>`). The action is never replayed. Each step is an audit entry (`step-up-started`, `step-up`, `step-up-refused`, and `request-refused` with reason `step-up-required` when the command is refused) carrying the user id and the intent; see "Audit log".

If SIT shows MCC SSO ignores `prompt=login`, set `annualva.step-up.mode: full-sign-in` (environment variable `ANNUALVA_STEPUP_MODE`): step-up then ends the app session and sends the person through a normal sign-in (ADR 0004). That only helps if the single sign-on session is also gone or re-authenticates; confirm with the SSO onboarding ticket.

## SIT onboarding checks (ticket 56)

The `sit` profile (`application-sit.yml`, values in `backend/.env.sit.example`) is ready; what MCC SSO in SIT actually does is confirmed by a person with SIT access, once the MTM onboarding has registered the login and service clients, the EC public key (kid in `ANNUALVA_MCC_SIGNING_KID`) and the redirect URI `https://<sit-host>/login/oauth2/code/mcc-sso-login`. Put the SIT CA certificate (PEM text) at `backend/src/main/resources/sit/mcc-sso-ca.crt`, or point `ANNUALVA_MCC_TRUST_CERTIFICATE` at another `classpath:` or `file:` location. Then sign in and read the ECS log lines (they carry user id and fixed codes only, never claim values):

| Question | How to check | What decides |
|---|---|---|
| Does MCC SSO supply `amr` or `acr` for MFA? | Sign in with `ANNUALVA_SIGNIN_REQUIRE_MFA_CLAIM=true`. | `event.action=sign-in` means yes. `sign-in-refused` with reason `mfa-not-evidenced` means the claim is absent or has other values: read the `amr`/`acr` values in the browser's id token (SIT test account), set `ANNUALVA_SIGNIN_MFA_AMR_VALUES` or `ANNUALVA_SIGNIN_MFA_ACR_VALUES`; only if MCC SSO supplies neither, record that and turn the check off for SIT (logged at startup as `sign-in-mfa-check-disabled`). |
| Is `email_verified` a boolean `true`? | Same sign-in. | Reason `email-not-verified` means it is missing, a string or false: record what is sent; only then set `ANNUALVA_SIGNIN_REQUIRE_VERIFIED_EMAIL=false` (logged at startup). |
| Is `auth_time` returned? | Same sign-in. | Reason `missing-auth-time`: the provider must be configured to return it; there is no application fallback (sign-in and step-up fail closed). |
| Is `afcas.uuid` mapped? | Same sign-in. | Reason `missing-identity`: the `afcas.uuid` mapper is missing on the client. |
| Does MCC SSO honour `prompt=login` and `max_age=0`? | Signed in, open `/auth/step-up?intent=sit-check&returnTo=/` in the same browser. | A password and MFA prompt appears and the log shows `step-up` success: yes. No prompt and `step-up-refused` with reason `stale-auth-time`: it does not. Set `ANNUALVA_STEPUP_MODE=full-sign-in`, re-run the same check through a sensitive action once ticket 10 exists, and update ADR 0004. If MCC SSO keeps its own browser session, an application-only sign-out is not enough: an end-session call to MCC SSO is then needed and is a new ticket. |

Record each answer under ticket 56's Comments.

## Roles and access

Roles are held in the app (table `role_grant`), never taken from token claims, and are read from the store on every request, so a revoked, lapsed, suspended or expired grant stops working on the next call (ADR 0011).

- **VA POCs:** `annualva.va-pocs` (environment variable `ANNUALVA_VA_POCS`) is a comma-separated list of exactly two distinct email addresses. The application refuses to start with fewer or more. At startup it grants each of them `VA_POC` (source `CONFIG`) and revokes a `CONFIG` grant for anyone no longer listed; changing a VA POC is a configuration change request plus a restart. `dev-mcc` lists the two VA POC personas.
- **SMO/Tech Leads and Cyber Managers:** granted per System by the System Register apply (ADR 0018). A grant made by email before the person has signed in attaches to them at their first sign-in.
- **Nobody grants themselves a role:** there is no endpoint that grants one, and an architecture test lists the only classes allowed to write `role_grant`.
- **Seeing a System:** every service path that addresses a System calls `AccessGuard` (ADR 0013); a System the caller may not see answers `404 not-found`, exactly like one that does not exist.
- **Front end:** `GET /api/session` lists the roles held, highest privilege first. The shell shows one nav entry per role and lands the person on the first role's area (`/va-poc`, `/cyber-manager`, `/smo`, `/auditor`); deep links need the host to serve `index.html` for those paths (a hosting decision, ticket 55).

## VA Cycle calendar

A VA Cycle is named after its starting year and has four Quarters, each starting on the first Monday of February, May, August and November and ending the day before the next one starts (12 to 14 weeks; Q4 ends the day before the next Cycle's Q1). A Quarter's Submission Window is weeks 1 and 2 (`submitDue` = start + 13 days), its Review Window weeks 3 and 4 (`reviewDue` = start + 27 days) and its Endorsement Window week 5 to its last day (`endorseDue`). A Cycle's `closesOn` is Q4's end + 28 days. Everything is computed in `Asia/Singapore` from the `Clock` bean and nothing is stored (ADR 0015).

- **First live Quarter:** `annualva.calendar.first-live-cycle` and `first-live-quarter` (2027 and 3: go-live on Monday 2 August 2027). Earlier Quarters of that Cycle, and every earlier Cycle, are `handledOutsideApp`. The application refuses to start without valid values.
- **API:** `GET /api/cycles` (the Cycles the app runs, the current one, today's business date) and `GET /api/cycles/{year}` (2000 to 2099; anything else is `404`), for anyone who holds a role. Each Cycle lists its Quarters with their windows and due dates, its `phase` (`UPCOMING`, `RUNNING`, `CLOSING`, `CLOSED`) and `current` (Quarter, week, window and the window's last day, or `null` when today is outside it).
- **Front end:** the header shows VA Cycle, Quarter, week and the current window, and says when the Quarter is handled outside the app.

## System Register template

A VA POC loads the System Register from a fixed Excel template (story 1, ADR 0016).

- **API:** `GET /api/system-register/template` (VA POC only; an SMO, Cyber Manager, Auditor or a person with no role gets `403`, no session `401`) returns `system-register-template.xlsx` as `Content-Disposition: attachment`. One sheet, five columns in this order: System ID, System Name, SMO/Tech Lead email, Cyber Manager email, Quarter (`Q1` to `Q4`); a hint comment on each header, a Quarter drop-down, an email shape check, text-formatted columns and one example row to delete before uploading.
- **Audit:** each download is a `REGISTER_TEMPLATE_DOWNLOADED` entry (ECS `register-template-downloaded`) with the user id and roles, written in the same transaction; it carries no System and no detail.
- **Front end:** the VA POC area links to the System Register screen (`/va-poc/register`), which has the Download template button under the classification banner.

## System Register upload and preview

A VA POC uploads the filled-in template and sees every change before anything happens (stories 2, 12 and 14, ADR 0017).

- **API:** `POST /api/system-register/uploads` (VA POC only, multipart field `file`, `.xlsx` up to 2 MB, the type decided from the bytes) answers `201` with a preview: an id, `expiresAt` (an hour), `canApply`, `counts` and `rows`. Each row has exactly one `outcome`: `ADD`, `UPDATE`, `RETIRE`, `NO_CHANGE` by System ID (case-insensitive), or `BLOCKED`, with `before` and `after` values, an `unlinkedSmo` flag and, when `BLOCKED`, fixed `reasons` codes. `GET /api/system-register/previews/{id}` reads it back (`404` unknown, `410` after the hour). Nothing in the Register changes until the apply (next section).
- **Blocked rows:** no Cyber Manager, the System's own SMO as its Cyber Manager, an invalid email or Quarter (only `Q1` to `Q4`), a duplicate System ID, a System ID starting `EXAMPLE-` (the template's sample row), or a missing name or System ID. A preview with any `BLOCKED` row has `canApply: false`. The preview is stored as one JSON list on `register_upload`.
- **The file is untrusted:** headers must be the template's, as written; macro workbooks, archives that unpack past 20 MB, empty sheets and sheets with more than 1000 rows are refused; formulas are never evaluated; an error never repeats the file. Each refusal is a fixed `problem+json` type (`unsupported-file-type`, `file-too-large`, `file-empty`, `register-header-mismatch`, `register-sheet-missing`, `register-no-rows`, `register-too-many-rows`, `register-file-unreadable`).
- **Audit:** `REGISTER_PREVIEWED` (the preview id and the row count), `REGISTER_PREVIEW_VIEWED` and `REGISTER_UPLOAD_REJECTED` (a fixed `reject_code`); never a cell.
- **Not malware-scanned yet:** the workbook is parsed without a scan because the scanner boundary does not exist; ticket 13 (pipeline) and 51 (adapter) own routing the Register upload through quarantine and scan (IM8 as-12, ADR 0017).
- **Front end:** the Register screen (`/va-poc/register`) has the file chooser, the grouped preview (Blocked, Add, Update, No change, Retire) and the Unlinked SMO flag.

## System Register apply and the Register table

A VA POC applies a confirmed preview with a fresh sign-in, and the SMO/Tech Lead and Cyber Manager roles follow the Register (stories 2 to 4, 73 and 79, ADR 0018).

- **API:** `POST /api/system-register/previews/{id}/apply` (VA POC only, step-up intent `register-apply`: a sign-in older than five minutes is refused with `403 step-up-required`) answers `200` with what it did (`added`, `updated`, `retired`, `unchanged`, `rolesGranted`, `rolesRevoked`). In one transaction ADD rows are added, UPDATE rows are changed (a retired System listed again is reinstated), RETIRE rows are marked retired (never deleted; history stays) and the role grants are reconciled. `404` for an unknown preview; `409` with `preview-applied`, `preview-expired`, `preview-blocked` or `register-changed` (the Register is no longer the one the preview was made from: two VA POCs can hold previews at once, so upload again).
- **Roles from the Register:** every active System has one SMO and one Cyber Manager grant, source `REGISTER`, scoped to it; by email where the person has not signed in (the first sign-in attaches it). A person the Register no longer names, and everyone on a retired System, loses the grant in the same transaction, so their next request is refused. VA POC grants come only from configuration and are never touched.
- **Audit:** `REGISTER_APPLIED` (preview id and counts), one `REGISTER_SYSTEM_RETIRED` per retired System and one `ROLE_GRANTED` or `ROLE_REVOKED` per grant (System, role, source and grant id); no email, name or System ID.
- **Reading the Register:** `GET /api/systems` (VA POC all; SMO/Tech Lead and Cyber Manager only their Systems; an Auditor is refused) lists `id`, `systemId`, `name`, `smoEmail`, `cyberManagerEmail`, `quarter` (`Q1` to `Q4`), `retired` and `unlinkedSmo` (recomputed on each read, shown to a VA POC only, `null` for anyone else).
- **Front end:** the Register screen shows the Apply button on a preview with no blocked row, explains the fresh sign-in (the step-up dialog, then the screen reopens the same preview to apply again), and shows the Register table, reloaded after an apply.

## Audit log

Every sign-in, sign-out, failed sign-in, step-up step and refused request (401 or 403, including a missing CSRF token and `step-up-required`) is a row in `audit_entry` (IM8 lm-4, story 84), and from now on so is every action a later ticket adds. A row holds the actor (user id), the roles held at the time, the action, the outcome (`SUCCESS`, `REFUSED`, `FAILED`), the System, VA Submission and Finding ids, the request's correlation id and an allow-listed `detail` (fixed codes, ids and counts: never a CVE id, rule name, host, comment, justification, email or name). Nobody can change or delete a row: a database trigger refuses `UPDATE`, `DELETE` and `TRUNCATE` for every connection, the application's own included (ADR 0014). Because the application's database user owns the table, the trigger is a guard against mistakes and application bugs, not against someone who holds that database login: run the application under a role that has `INSERT` and `SELECT` only on `audit_entry`, and migrations under a separate owner (hosting, ticket 55).

- **Writing:** `AuditLog.record(event)` joins the caller's transaction (and fails if there is none), so the entry commits and rolls back with the action. `AuditLog.recordIndependently(event)` commits on its own; the access module uses it for sign-in and refusals. A refusal is recorded where the 401 or 403 is written, so it is kept even when the command it refused had rolled back. A request that answers 404 for a System the caller may not see (ADR 0013) is not a refusal and is not recorded. A 429 from the anonymous-session cap is not recorded (it stays an ECS warning), so a flood of throttled requests does not become a flood of rows. If an independent write fails, the application logs `audit-write-failed` with the exception type only and carries on.
- **Reading:** `GET /api/audit-entries` (VA POC only), newest first, with `actor`, `action`, `system`, `submission`, `from` and `to` (business dates in `Asia/Singapore`, both inclusive), `page` (from 0) and `size` (default 50, at most 200). The VA POC area of the front end has the viewer at `/va-poc/audit-log`; it shows Finding ids and no Finding detail. Cyber Manager history (ticket 48) and Auditor scope (Release 2) come later.
- **ECS events:** each entry is also emitted, after its transaction commits, on the logger `annualva.audit` (so it can be routed apart from the application log) with `event.action` (`sign-in`, `sign-out`, `sign-in-refused`, `step-up-started`, `step-up`, `step-up-refused`, `step-up-full-sign-in`, `request-refused`, `role-granted`, `role-revoked`, `register-template-downloaded`, `register-previewed`, `register-preview-viewed`, `register-upload-rejected`, `register-applied`, `register-system-retired`), `event.outcome`, `event.reason`, `user.id`, `user.roles`, `trace.id` and the ids and detail as `labels.*`. A step-up that is needed is now `request-refused` with reason `step-up-required` (it was the event `step-up-required`).
- **Adding an action:** add a constant to `AuditAction`; add any new detail key to both `DetailKey` and the CHECK constraint `audit_entry_detail_check` (a new migration); call `record` from the command's transaction. A detail value must be a short code, an id or a number (`AuditDetail` refuses anything else).
- **Retention:** entries are kept for the VA Cycle plus 3 years; the retention ticket (49) replaces the trigger by a migration when disposal exists. Nothing in the application can switch the trigger off.

## Quality gates

| Gate | Command | Passes when |
|---|---|---|
| Back end | `cd backend && ./mvnw -B -ntp clean verify` | Seam 1 tests green on Testcontainers; ArchUnit green; JaCoCo line and branch coverage of at least 80 % (`backend/target/site/jacoco/index.html`) |
| Front end | `cd frontend && npm run check` | Prettier, `tsc -b`, `vite build`, no raw HTML rendering, dependency-cruiser rules, fallow dead-code and health |
| Review | `/code-reviewer` on the ticket's changes | report under `artifacts/code-reviewer/`, no Critical or High finding |

## Repository layout

```
backend/            Spring Boot API (one package per module under sg.annualva)
frontend/           React SPA (src/app shell, src/api typed client, src/components, src/screens/<role>)
scripts/            dev key generation, LocalStack init, MockPass personas, dev data
docs/adr/           architecture decision records
docs/agents/        agent conventions and human reviewer decisions
.scratch/annual-va/ spec, structured stories, tickets
artifacts/, reports/ skill outputs (code review reports, dependency orchestrator reports)
compose.yml         local platform (mock-m-sso services verbatim + postgres)
```

## Troubleshooting

- **`PKIX path building failed` inside the mock containers:** corporate TLS proxy. Copy `compose.override.yml.example` to `compose.override.yml` and point it at your root CA.
- **Browser refuses `https://localhost:5173` or `:8443`:** accept the self-signed certificate once for each port.
- **Init scripts do nothing in LocalStack:** check they have LF line endings (`.gitattributes` enforces it) and the executable bit.
- **`../mock-m-sso` missing:** `docker compose up -d postgres localstack` still works; sign-in, scanning and mail need the sibling repo.
- **Port already in use:** stop the other process; the dev servers use strict ports.
