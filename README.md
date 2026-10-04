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
| `sit` | added by the first SIT-bound ticket | `${VAR:dev-default}` plus `.env.sit.example` |

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
