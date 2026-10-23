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

- **Malware verdicts (mock-SFS):** the verdict follows a keyword in the file's display name: `quarantined` is blocked, `sanitized` is rejected (the app does not accept a modified file), `unchanged` or anything else is allowed unchanged. Upload `report_sanitized.xml` to see the "modified during scanning" rejection. Upload `report_quarantined.xml` to see a malware rejection. `SFS_SCAN_DELAY_MS` in `compose.yml` sets the scan delay.
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
- **The file is untrusted:** headers must be the template's, as written; macro workbooks, archives that unpack past 20 MB, empty sheets and sheets with more than 1000 rows are refused; formulas are never evaluated; an error never repeats the file. Each refusal is a fixed `problem+json` type (`unsupported-file-type`, `file-too-large`, `file-empty`, `register-header-mismatch`, `register-sheet-missing`, `register-no-rows`, `register-too-many-rows`, `register-file-unreadable`), and, from the malware scan, `file-rejected-malware`, `file-modified-by-scan` (the scanner had to change the file; upload the original again), `scan-timeout` and `scan-mismatch`.
- **Audit:** `REGISTER_PREVIEWED` (the preview id and the row count), `REGISTER_PREVIEW_VIEWED` and `REGISTER_UPLOAD_REJECTED` (a fixed `reject_code`); never a cell.
- **Scanning needs the real adapters (ticket 51):** the Register workbook and every Scan Report file are quarantined and malware-scanned before they are read (ADR 0017, ADR 0021), but the object-store and scanner adapters are not built yet. Until then they fail closed (`adapters.pending`), so an upload is refused rather than read unscanned; Seam 1 tests use the fakes. A file the scanner had to modify (Sanitized) is rejected, not accepted: the person uploads the original again. Tune the scan with `annualva.scan.attempt-timeout`, `poll-interval`, `max-in-flight`, `sync-wait` and `sync-poll-interval`.
- **Front end:** the Register screen (`/va-poc/register`) has the file chooser, the grouped preview (Blocked, Add, Update, No change, Retire) and the Unlinked SMO flag.

## System Register apply and the Register table

A VA POC applies a confirmed preview with a fresh sign-in, and the SMO/Tech Lead and Cyber Manager roles follow the Register (stories 2 to 4, 73 and 79, ADR 0018).

- **API:** `POST /api/system-register/previews/{id}/apply` (VA POC only, step-up intent `register-apply`: a sign-in older than five minutes is refused with `403 step-up-required`) answers `200` with what it did (`added`, `updated`, `retired`, `unchanged`, `rolesGranted`, `rolesRevoked`). In one transaction ADD rows are added, UPDATE rows are changed (a retired System listed again is reinstated), RETIRE rows are marked retired (never deleted; history stays) and the role grants are reconciled. `404` for an unknown preview; `409` with `preview-applied`, `preview-expired`, `preview-blocked` or `register-changed` (the Register is no longer the one the preview was made from: two VA POCs can hold previews at once, so upload again).
- **Roles from the Register:** every active System has one SMO and one Cyber Manager grant, source `REGISTER`, scoped to it; by email where the person has not signed in (the first sign-in attaches it). A person the Register no longer names, and everyone on a retired System, loses the grant in the same transaction, so their next request is refused. VA POC grants come only from configuration and are never touched.
- **Audit:** `REGISTER_APPLIED` (preview id and counts), one `REGISTER_SYSTEM_RETIRED` per retired System and one `ROLE_GRANTED` or `ROLE_REVOKED` per grant (System, role, source and grant id); no email, name or System ID.
- **Reading the Register:** `GET /api/systems` (VA POC all; SMO/Tech Lead and Cyber Manager only their Systems; an Auditor is refused) lists `id`, `systemId`, `name`, `smoEmail`, `cyberManagerEmail`, `quarter` (`Q1` to `Q4`), `retired` and `unlinkedSmo` (recomputed on each read, shown to a VA POC only, `null` for anyone else).
- **Front end:** the Register screen shows the Apply button on a preview with no blocked row, explains the fresh sign-in (the step-up dialog, then the screen reopens the same preview to apply again), and shows the Register table, reloaded after an apply.

## SMO inbox

An SMO/Tech Lead sees their Systems and what to do by when (stories 18 and 71, ADR 0019).

- **API:** `GET /api/dashboards/smo-inbox?cycle=` (SMO/Tech Lead only; a Cyber Manager, VA POC, Auditor or a person with no role gets `403`, no session `401`) answers `cycle` and two lists. `systems` holds the caller's own active Systems in Quarters the app runs, by Quarter then System ID, each with `quarter`, `state` (`NOT_STARTED`, or `DRAFT` once the VA Submission is started), `submissionId` (null until it is started), `submitDue`, `reviewDue`, `endorseDue`, `nextAction` (`START_SUBMISSION`, or `PREPARE_SUBMISSION` for a Draft), `nextActionDueOn` and the `group` it is listed under (`NEEDS_YOUR_ACTION`, `WAITING_ON_OTHERS` or `DONE`). `handledOutsideApp` holds Systems in Quarters before the first live Quarter, with id, name and Quarter only. `cycle` omitted means the Cycle running today (before the first live Cycle starts, that Cycle); a year the app does not run is `404`, a value that is not a year `400`.
- **Scope:** the caller's Systems come from the access guard (`Need.SUBMIT`), so nobody sees another person's System; a VA POC who is also named as an SMO sees only the Systems they are SMO for.
- **Not audited:** the inbox names no report or Finding, like the Register list (ADR 0018).
- **Front end:** the SMO area (`/smo`) groups the Systems under Needs your action, Waiting on others and Done, lists Systems handled outside the app apart, shows an Overdue pill when the server sends `overdue`, offers a Cycle selector while two Cycles are open, and has a Start button per Not Started System that starts the VA Submission and opens its prepare screen, and a Continue link per Draft.

## VA Submission

An SMO/Tech Lead starts the VA Submission for one of their Systems (story 19, ADR 0020).

- **Start:** `POST /api/submissions` with `{"systemId": "<the System's id>", "cycle": <year, optional>}` (SMO/Tech Lead only; a Cyber Manager, VA POC who is not the System's SMO, Auditor or a person with no role gets `403`; an SMO of another System, or a System that does not exist or is retired, gets `404 not-found`) answers `201` with the Draft and its address. `cycle` omitted means the Cycle running today (before the first live Cycle starts, that Cycle); a year the app does not run is `404`. It is allowed before the Quarter begins. There is one VA Submission per System and Cycle: a second start, or the start of a System whose Quarter is handled outside the app, is `409 invalid-transition`.
- **Read:** `GET /api/submissions/{id}` (the System's SMO/Tech Lead or Cyber Manager, or a VA POC; anyone else `404`, an Auditor `403`) answers `state`, `overdue` and `endorsedLate` (both false until the later tickets that set them), `submitDue`, `reviewDue`, `endorseDue` (copied from the Quarter when the submission starts), `startedAt` and `acted`: who first acted at which step (`SUBMIT` for the person who started it), as user ids and times, never names or emails.
- **Segregation of duties:** starting records the caller in `submission_participation` at the `SUBMIT` step; later tickets check it before a person acts at another step.
- **Audit:** a start is a `SUBMISSION_STARTED` entry (SUCCESS) about the System and the VA Submission; a refused start is the same action with outcome `REFUSED` and a fixed `reason` (`already-started` or `quarter-handled-outside-app`). A read of one VA Submission by a Cyber Manager or a VA POC is a `SUBMISSION_VIEWED` entry (ids only, no detail); the System's own SMO/Tech Lead reading it writes none, and the inbox and lists are not audited (ADR 0019, ADR 0020).
- **Supporting evidence (story 22, ADR 0026):** a PDF or HTML report is attached to a Scan Report with the same `POST /api/submissions/{id}/files`, `kind=EVIDENCE` (any of the three Scan Reports; PDF up to 20 MB, HTML up to 10 MB, at most 20 per Scan Report). It is quarantined, malware-scanned and type-checked from its leading bytes (`%PDF-` at the very start, or an HTML doctype or `<html>` tag in the first 4 KB), never parsed or read, and rests in `READY`; anything else is `REJECTED_TYPE`; a PDF whose tail names `/Encrypt` is `REJECTED_ENCRYPTED` (a byte check, nothing is parsed). `GET .../files/{fileId}/content` serves every file, evidence included, only as an attachment (`application/octet-stream`, `nosniff`, `Content-Security-Policy: default-src 'none'; sandbox`): the app never renders an evidence file, and the download is audited for every role. An evidence download is always offered as `.pdf` or `.html` by what its bytes are, never under the extension it was uploaded with.
- **Findings and Finding Statuses (stories 28 and 35, ADR 0027):** `GET /api/submissions/{id}/findings` lists the Findings of a VA Submission (anyone who may see the System), the most severe first, in pages of up to 500; it filters by `reportType`, `severity` (repeat it or separate values with commas), `reviewState` and `needsAttention` (no proposal yet, or Disputed), and each Finding carries `reviewState` and `currentProposal`. `POST /api/findings/{id}/proposals` (the SMO/Tech Lead of the System, in Draft or Returned for Rework) proposes Remediated with `evidence`, False Positive with `justification`, Risk Accepted with `reason`, `approverName`, `approverRole` and an optional `approvalMemoFileId` (a READY supporting evidence file of the same VA Submission, uploaded with `kind=EVIDENCE`), or Remediation Planned with `plan` and a `targetDate` after today in Singapore (at most ten years ahead). A field that is missing, not used by the status, too long (4000 characters; names and roles 200) or malformed is a 400 `invalid-field` problem whose `field` names it. Every proposal is a new row that a database trigger keeps from ever changing (`GET /api/findings/{id}/proposals` reads the history), the Finding points at the latest and its review state goes back to `PENDING`. A proposal is a `STATUS_PROPOSED` audit entry (ids, the status code and the memo file id; no text), and a read of the history is `FINDING_PROPOSALS_VIEWED` for every role.
- **Bulk-set and progress (stories 36 to 38, ADR 0028):** `POST /api/submissions/{id}/findings/bulk-proposals` (the SMO/Tech Lead of the System, in Draft or Returned for Rework) takes `findingIds` (1 to 1000 distinct ids) and one status with its fields in the same body as a single proposal, and answers `201` with `batchId` and `count`. It makes one `bulk_batch` (append-only, like the proposals) and one proposal per Finding carrying that `batchId` (each proposal now shows its `batchId`, null for a single one). The list is refused as a whole with a 400 `invalid-field` naming `findingIds` when it names anything that is not a Finding of this VA Submission; the fields are checked as for one Finding. A batch is one `BATCH_PROPOSED` audit entry (`batch_id`, `count`, `finding_status`, the memo's `file_id`) plus one `STATUS_PROPOSED` entry per Finding carrying the `batch_id`; a refusal for the state is the same action with outcome `REFUSED`. `GET /api/submissions/{id}/readiness` (anyone who may see the System) answers `total`, `proposed`, `unproposed` (no proposal yet), `awaitingReview`, `disputed`, `carriedOver` and `targetDateMissed` (both 0 until Release 2); a read by a Cyber Manager or a VA POC is a `READINESS_VIEWED` entry with ids only.
- **Replacing a file with a rescan (story 39, ADR 0029):** `POST /api/submissions/{id}/files/{fileId}/replace` (the SMO/Tech Lead of the System, in Draft or Returned for Rework; multipart `file` only) takes a rescan of one parsed report file and answers `202` with the rescan's status; it walks the whole pipeline and is not counted against the 20 files. Nothing changes until it is parsed, and a rejected rescan changes nothing. Once it is parsed, in one transaction, the Findings are compared with the old file's places only: a key it still reports keeps its Finding, proposals and review with the rescan's places (and its severity from the places it has now), a key it no longer reports loses the old file's places, a Finding then reported by no file gets a Remediated proposal with `proposedAs` `RESCAN` whose evidence names the rescan, the scan date its report gives and its upload date, and whose `replacedFile` names the old file (review back to `PENDING`; a Finding already proposed Remediated is left as it is), and a new key is a new Finding. A later file that reports such a Finding again takes the rescan's proposal away as current (the history keeps it; a `PROPOSAL_WITHDRAWN` entry by the uploader with `file_id` and `proposed_as`), so the Finding needs attention again. Settlements of one VA Submission run one at a time (a transaction-scoped advisory lock on the VA Submission), and each locks the Findings it will write once, in id order, before writing. The old file stays `PARSED`, downloadable and marked `supersededBy`; the rescan carries `replacesId`; retention (ticket 49) lists superseded files once the VA Submission is Endorsed. A file that is not a parsed report file or is already replaced is `409 file-not-replaceable`, a second rescan while one is being checked `409 replace-in-progress`, a VA Submission that takes no files `409 invalid-transition`. The upload is a `FILE_UPLOADED` entry with `file_id` and `replaced_file_id` (refusals with a `reason`), the replacement a `FILE_REPLACED` entry by the scan job, and each rescan proposal a `STATUS_PROPOSED` entry with `proposed_as` `RESCAN`, `rescan_file_id` and `replaced_file_id` (every `STATUS_PROPOSED` entry now carries `proposed_as`, and on a proposal `file_id` is only the approval memo); these name the uploader as the actor. A Finding's severity is rated from its current places after every settlement.
- **Not applicable and Findings entered by hand (story 23, ADR 0030):** `GET /api/submissions/{id}/reports` (anyone who may see the System) gives the three Scan Reports with `mode` (`UPLOAD`, the default, `NOT_APPLICABLE` or `MANUAL`), `justification` and `reviewState` (`PENDING`, `CONFIRMED` or `DISPUTED`, for a not-applicable one only); every read is a `SCAN_REPORTS_VIEWED` entry with the count. `PUT /api/submissions/{id}/reports/{type}/mode` (the SMO/Tech Lead of the System, in Draft or Returned for Rework) takes `mode` and, for `NOT_APPLICABLE` (Dependency-Check or Burp Suite only; Secure Configuration is a 400 `invalid-field` naming `mode`) and `MANUAL`, a `justification` of up to 4000 characters; a not-applicable justification goes to `PENDING` for the Cyber Manager whenever it changes. The mode changes only while the Scan Report holds no report file that was not rejected and no Finding (`409 report-not-empty`); setting the mode it already has always works. A Scan Report not uploaded refuses report files (`409 wrong-report-mode`) and still takes supporting evidence. `POST /api/submissions/{id}/findings/manual` (same caller and states, Scan Report in `MANUAL`) takes `reportType`, `title` (200 characters), `identifier` (200), `packageOrRule` (500) and `severity` and answers `201` with a Finding whose `source` is `MANUAL` (every parsed Finding says `PARSED`); the same identifier and package or rule twice is `409 duplicate-finding`. Mode changes are `REPORT_MODE_SET` entries (`report_type`, `mode`) and entries by hand `FINDING_ENTERED` entries (`report_type`), refusals with a `reason`; no text a person typed is audited or logged.
- **Scan date, target and the age rules (stories 25 and 26, ADR 0031):** the pipeline's last stage is the age check: a report whose scan date is after today's business date in Singapore is `REJECTED_FUTURE_DATED` after the parse (stages `SCAN_CLEAN`, `TYPE_OK`, `SCHEMA_OK`, `REJECTED_FUTURE_DATED`; no Findings kept; a `FILE_REJECTED` entry with `reject_code` `future-dated`). `GET /api/submissions/{id}/files/{fileId}/metadata` now also gives `scanDay` (the business date of the file's scan date), `confirmedScanDate`, `confirmedTarget`, `scanDateEdited` and `targetEdited`. `PUT` on the same path (the SMO/Tech Lead of the System, in Draft or Returned for Rework, for a parsed report file) takes `scanDate` (yyyy-mm-dd, not after today, at most ten years back) and `target` (one line, up to 200 characters) and answers `200` with the metadata; a scan date or target that differs from the file's is flagged (`scanDateEdited`, `targetEdited`), never refused, and the confirmed date is the one judged; a file a rescan replaced is `409 file-superseded`. Each confirmation is a `FILE_METADATA_CONFIRMED` entry with the `file_id` and the flags `date_changed` and `target_changed` (refusals with a `reason`); no date or target is logged or audited. `GET /api/submissions/{id}/readiness` also names, as `{fileId, name, reportType}` in upload order, the report files more than 90 days old at today's business date (`tooOld`) and those with no scan date to judge (`undated`: none in the file and none given, or only a Burp export time), judging the confirmed scan date before the file's; only parsed report files that no rescan replaced count.
- **Declaration and submit (stories 40 and 41, ADR 0032):** `GET /api/declaration-text` (SMO/Tech Lead, Cyber Manager, VA POC) answers the current Declaration `version`, `text` and `placeholder`; version 1 is a placeholder seeded by V19 until ticket 24 adds the agreed wording as a new version by migration (the highest version is current, and no version is ever changed). `POST /api/submissions/{id}/submit` (the SMO/Tech Lead of the System, in Draft or Returned for Rework, else `409 invalid-transition`) takes `declarationAccepted` and `declarationVersion` (the current version) and answers `200` with the VA Submission `IN_REVIEW`, its `submittedAt` and the `declaration` accepted (`version`, the exact `text`, `acceptedBy`, `acceptedAt`), which `GET /api/submissions/{id}` also gives. While anything stands in the way it is one `409 not-ready` whose `reasons` list each as `{reason, message, reportType, files}`, in the order `files-scanning`, `report-missing` (per Scan Report: Secure Configuration needs a report file that was read or Findings entered by hand; Dependency-Check and Burp Suite may also be not applicable), `findings-unproposed`, `files-too-old`, `files-undated`, `files-future-dated`, then `declaration-not-accepted` or `declaration-changed`; `GET /api/submissions/{id}/readiness` gives the same list, without the Declaration, as `blockers`. The submit holds the VA Submission's row, and every write to what the SMO/Tech Lead prepares holds it shared, so nothing changes between the checks and the move. A submit is a `SUBMISSION_SUBMITTED` entry with `state_from`, `state_to` and `declaration_version` (refusals with a `reason` and, for `not-ready`, the `count`), records the caller at step `SUBMIT`, and publishes a `SubmissionEvent` after the commit. Afterwards every change to the files, Findings, proposals, scan dates and Scan Report modes is `409 invalid-transition`, and the screen is read-only.
- **Front end:** `/smo/submissions/{id}` is the prepare screen: the state and dates, a Scan Report card for each of the three reports with how it is provided (a select of upload, entered by hand and, except for Secure Configuration, not applicable, with a justification box and the review of a not-applicable one), a form to enter a Finding by hand when its tool cannot export one, its report files while it is uploaded and, apart from them, its supporting evidence (upload, per-file status and rejection message, download, and Replace with a rescan for a report file whose findings were read), the scan date and target of each parsed report file, pre-filled from the file for the SMO/Tech Lead to confirm or correct (a Burp export time is said to be only that, and an edited scan date or target is marked), Findings entered by hand marked "Manually Entered", the Findings as a spreadsheet-style list with filter chips (Needs attention, All, Disputed, Critical/High), a progress bar with the files too old to submit or with no scan date yet named under it, tick boxes with a select-all, a status select on each row and the fields the chosen status requires (each with its classification label), a sticky bulk bar that applies one status to every ticked Finding, and a disabled Declaration until the submit ticket fills it.

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
