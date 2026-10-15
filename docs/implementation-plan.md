# Annual VA: implementation plan (Spring Boot + React)

## Context

`D:\projects\annualVA2` holds only `spec.md` and `spec.pdf` (same content): the Gate-1-approved TRD for an internal app that runs the organisation's annual vulnerability assessment (VA) cycle. About 40–48 Systems each submit three scan reports and a declaration once a year in their assigned Quarter; a Cyber Manager reviews every Finding, one of two VA POCs endorses, and everything is audited and retained. Today this is done by hand with sensitive files circulating loosely.

The sibling repo `D:\projects\annual-va` is an earlier attempt (~20–25 % of Release 1). The user has decided this repo is a **clean-room restart**: no code or tickets copied from it; it is read-only reference for lessons. Other user decisions:

| Decision | Choice |
|---|---|
| Stack | Spring Boot 4.0.x on Java 21 (only JDK 21 installed), Maven wrapper, `JdbcClient` + Flyway SQL (no JPA), PostgreSQL 17, Apache POI, hardened JDK XML. Frontend: Vite + React 19 + TypeScript strict, plain `fetch`, no router/UI/data library, no React unit tests. |
| Local platform | The MCC mock stack `mock-m-sso` (Keycloak + MockPass SSO, mock-SFS scanner, mock-MCNS mail, LocalStack S3/KMS) from day one, plus Postgres. |
| Deliverable | Architecture + phased build order, and the first implementation step seeds the `.scratch/annual-va/` tracker so `/do-work` can run ticket by ticket. |

**Blocker found during planning:** `mock-m-sso` has no recorded git URL anywhere in `D:\projects\App-Standards` and is not on this machine. Obtaining it is human prerequisite H0. Seam-1 tests do not need it (they fake the boundary adapters), so the skeleton and backend slices proceed; browser demos against SSO/SFS/MCNS wait for it.

Sources consulted: `spec.md`; `D:\projects\App-Standards` (retired bootstrap standard recovered from git at `ff5ab82^`, SSO/Shared-Auth, File/SFS, MCNS, Logging standards); `D:\projects\docs\agents\*.md` (tracker conventions); installed skills (`do-work`, `build-check`, `code-reviewer`, `e2e`, `to-tickets`, `convert-stories`, `dependency-orchestrator`).

## Status (updated 2026-10-05)

Step 1 of this plan (section 8) is done and committed on `main` (8 commits, clean tree):

| Area | State |
|---|---|
| Scaffold | Repo layout, `compose.yml` (verbatim mock blocks + Postgres), LocalStack init scripts, dev keys, personas, README, AGENTS, CONTEXT, ADRs 0001 to 0012, reviewer decisions |
| Backend skeleton | Spring Boot 4.0.8 / Java 21, deny-by-default chain, synchronizer CSRF, RFC 9457 problems with traceId, IM8 headers, ECS logs, Seam 1 harness (Testcontainers, mutable clock), ArchUnit; `./mvnw verify` green with 22 tests and the coverage gate met |
| Frontend skeleton | Vite 8 + React 19 + TypeScript 5.9, shell with classification banner, typed fetch client; `npm run check` green |
| Planning artefacts | `spec.stories.yaml` (99 stories), dependency DAG and dev report (`reports/`), `artifacts/dag-repair-notes.md` |
| Tracker | 59 Release 1 tickets in `.scratch/annual-va/issues/` (section 7), vetted; ticket 02 closed, 01, 24 and 54 to 59 ready-for-human, the rest ready-for-agent |
| Verified | Postgres and LocalStack up with the KMS alias and the SSE-KMS buckets; the backend under `dev-mcc` reaches the database step without `mock-m-sso` present |
| Not verified | Browser sign-in, scanning and mail (need ticket 01); backend start against Postgres end to end (Docker Desktop was down at the last check) |

Deviations from the plan as first written, each recorded where it applies: the shared properties record lives in a leaf `config` package; the frontend code-health gate runs `fallow dead-code && fallow health` over the whole project because `fallow audit` needs a git base branch a new repository lacks; TypeScript is pinned to 5.9 for dependency-cruiser compatibility; malware-infected quarantine bytes are discarded rather than retained; Will move and Will transfer Register rows are classified but blocked in Release 1; the orchestrator's merged DAG needed hand repair (exclusive node ownership, 17 edge cuts) and its report generator a one-line patch.

Next action: `/do-work` picks ticket 03 (sign in through MCC SSO). Ticket 01 (obtain `mock-m-sso`) is the human prerequisite for every browser demo.


---

## 1. Repository layout

```
annualVA2/
├── AGENTS.md  CONTEXT.md  README.md  compose.yml  .gitignore  .gitattributes
├── .claude/launch.json                 # preview_start: frontend (5173), backend (8443)
├── docs/agents/{issue-tracker,triage-labels,domain,reviewer-decisions}.md
├── docs/adr/NNNN-*.md
├── .scratch/annual-va/{spec.md, spec.pdf, spec.stories.yaml, README.md, issues/NN-slug.md}
├── artifacts/                          # skill outputs (code-reviewer, do-work, e2e, orchestrator)
├── reports/                            # dependency-orchestrator dev-report
├── scripts/{gen-dev-keys.sh, localstack-init/01-kms-key.sh, localstack-init/02-buckets.sh,
│            mock-mockpass-assertions.js, dev-data/system-register.sample.xlsx}
├── backend/  (mvnw, pom.xml, src/main/java/sg/annualva/<module>/, src/main/resources/{application.yml,
│             application-dev-mcc.yml, dev-keystore.p12, db/migration/V*.sql},
│             src/test/{resources/application-ci.yml, java/sg/annualva/{harness,arch,<module>}})
├── frontend/ (vite.config.ts, tsconfig.json, .prettierrc.json, .dependency-cruiser.cjs, src/...)
└── e2e/      (late Release 1: Playwright + allure, per the e2e skill)
```

- `spec.md`/`spec.pdf` move to `.scratch/annual-va/` (the tracker convention); root `AGENTS.md`/`README.md` point there. The spec's relative links (`map.md`, `issues/..`, `../../CONTEXT.md`) refer to the original wayfinder repo and are intentionally not reproduced; `.scratch/annual-va/README.md` says so.
- `.gitignore`: OS/IDE files, `target/`, `dist/`, `node_modules/`, `*.tsbuildinfo`, `frontend/.fallow/`, `compose.override.yml`, `.env.*` with `!.env.*.example`, `*.pem`, `artifacts/e2e/`, `artifacts/**/cache/`, `e2e/{test-results,allure-results,allure-report}/`, `*.log`, `.agents/`, `.claude/` (except `launch.json` if desired), `agent/`, `skills-lock.json`. `.scratch/`, `artifacts/code-reviewer/`, `reports/` are committed.
- `.gitattributes`: `*.sh text eol=lf` (CRLF breaks the LocalStack init scripts in the container).
- Base package `sg.annualva` (change in the scaffold ticket if the org wants another).

---

## 2. Backend architecture

### 2.1 Modules (flat, one package per deep module; ArchUnit enforces the arrows)

| Package | Owns | Depends on |
|---|---|---|
| `app` | `@SpringBootApplication`, security filter chain, session/CSRF, virtual threads, scheduling switch, profile guard | everything |
| `web` | RFC 9457 `ProblemDetail` advice (+`traceId`), correlation-id MDC filter, security headers (CSP, HSTS…), pagination | none |
| `config` | Validated configuration records shared by more than one module (prefix `annualva`); leaf | none |
| `boundary` | The five adapter interfaces: `IdentityProvider`, `MalwareScanner`, `MailRelay`, `FileStore`, `java.time.Clock` bean | none |
| `audit` | Append-only `audit_entry` (DB trigger), `AuditLog.record`, `recordRefused` (REQUIRES_NEW), scoped read API, ECS audit events | boundary |
| `cycle` | VA Cycle/Quarter calendar, windows, first live Quarter, `BusinessTime` in `Asia/Singapore` | none |
| `access` | `app_user`, `role_grant`, sign-in linking, `AccessGuard` (ownership), `StepUp`, lapse/removal/review, VA POC config, Auditor grants | cycle, audit, boundary |
| `register` | xlsx template, change preview (ADD/UPDATE/RETIRE/MOVE/CHANGE_SMO/CHANGE_CM → NONE/WILL_WITHDRAW/WILL_MOVE/WILL_TRANSFER/BLOCKED), apply, Unlinked SMO / SMO Unreachable flags; port `InFlightWork` | access, cycle, audit |
| `finding` | Finding Ledger: identity keys, occurrences, proposals, reviews, bulk batches, manual entries, N/A slots, readiness counts; port `EditGate` | access, cycle, audit, boundary(Clock) |
| `parser` (+`dependencycheck`, `scap`, `nessus`, `burp`, `xml`) | `ReportParser`: bytes in → `ParsedReport{metadata, findings[]}`; `SecureXml` factories; `ParserRegistry` | none |
| `ingestion` | Upload pipeline, `report_file` lifecycle, scan poller, downloads; implements `submission.EvidenceReadiness` | submission, finding, parser, access, audit, boundary, web |
| `submission` | State machine (one method per command), windows, Overdue, Endorsed Late, cycle close, exemptions, SoD guard, declaration; implements `register.InFlightWork`, `finding.EditGate`; publishes `SubmissionEvent`s | register, access, finding, cycle, audit, boundary |
| `notification` | Reminder planner, event messages, in-app list, mail outbox, POC pair clearing, bounce handling. Never imports `finding` | register, access, cycle, audit, boundary |
| `dashboard` | Read models: SMO inbox; CM inbox + Quarter board; POC board + Cycle matrix + Exemption queue | all domain |
| `export` | Async export jobs, xlsx (POI streaming) + PDF (OpenPDF) writers, watermark | submission, finding, register, audit, access, cycle, boundary |
| `retention` | Due computation, disposal list, approval, hard delete + tombstone | submission, ingestion, export, access, audit, boundary |
| `adapters.{oidc,mcc,sfs,mcns,s3}` | Production implementations of `boundary`: MCC SSO identity, M2M token + JWKS + outbound `RestClient`, SFS scanner, MCNS relay, S3 store (SSE-KMS) | boundary, web |

### 2.2 Data model (Flyway, one migration per slice; `uuid` PKs, `timestamptz` UTC, `citext` emails, check constraints)

| Table | Key columns |
|---|---|
| `app_user` | email unique, sso_id (afcas.uuid) unique null, display_name, linked_at, idp_disabled_at |
| `role_grant` | role (SMO, CYBER_MANAGER, VA_POC, AUDITOR), user_id null, email, system_id null, source (CONFIG, REGISTER, GRANT), granted_at/by, expires_at, last_used_at, lapsed_at, suspended_at, revoked_at |
| `auditor_scope` | grant_id, system_id null, cycle_year null |
| `va_cycle` | year pk, closed_at/by, closes_on override. Added by ticket 41; until then the calendar (q1..q4_start, q4_end, closes_on = q4_end+28) is computed and the first live Quarter is configuration, not a column (ADR 0015, reviewer-decisions.md) |
| `va_system` | id (uuid), code (business System ID, citext unique), name, smo_email, cm_email, quarter, added_on, retired_at; `smo_unreachable(_since)` added by ticket 39. Created by ticket 08 (read-only there); written by the apply of ticket 10 (ADR 0018), which also gave `role_grant.system_id` its foreign key (migration V7); Unlinked SMO is read from `app_user`, not stored (ADR 0017) |
| `register_upload` | upload status (PREVIEW/APPLIED/DISCARDED), created_by/at, expires_at, and `rows` jsonb: the whole preview as one list, each row with one outcome (ADD, UPDATE, RETIRE, NO_CHANGE, BLOCKED) and its reasons, before/after (ADR 0017); no `register_preview_row` table |
| `va_submission` | system_id, cycle_year, quarter, state (10 states), previous_state, overdue(+since), endorsed_late, submit_due, review_due, endorse_due, windows_reset_on, started/submitted/endorsed_at/by, version; unique(system_id, cycle_year) |
| `submission_participation` | (submission_id, user_id, step SUBMIT/REVIEW/DECIDE) pk, first_at — the SoD record |
| `submission_return` | by_role (CM/VA_POC), comment, at |
| `declaration_text`, `declaration` | versioned text (human-owned); per-submission snapshot + accepted_by/at |
| `report_slot` | (submission_id, report_type) pk, mode (UPLOAD/NOT_APPLICABLE/MANUAL), justification, review_state/comment; SECURE_CONFIGURATION may not be NOT_APPLICABLE |
| `report_file` | report_type, kind (MACHINE_READABLE/EVIDENCE), detected_format, size, upload_sha256, clean_sha256, dirty_ref, clean_ref, pipeline_status, reject_code, scan_ticket, scan_attempts, scan_date_from_file/confirmed, target_from_file/confirmed, superseded_by, deleted_at |
| `finding` | submission_id, system_id, report_type, identity_key/hash, title, identifier, package_or_rule, severity (CRITICAL..INFORMATIONAL), confidence, source (PARSED/MANUAL), not_completed, carried_from, carried_over, needs_rejustification, target_date_missed, current_proposal_id, review_state; unique(submission_id, identity_hash) |
| `finding_occurrence` | finding_id, report_file_id, locator, severity, result_raw, not_completed |
| `bulk_batch`, `finding_status_proposal`, `finding_review` | batch metadata; proposal (status + per-status fields, proposed_as SMO/PREVIOUS_SMO/CARRY_OVER/RESCAN, batch_id); review (CONFIRMED/DISPUTED, comment, as_role CM/VA_POC) |
| `exemption` | basis (PRIOR_VA/SCHEDULED_VA), va_date, evidence, decision, conditional, va_done_reported/confirmed, lapse_deadline, lapsed_at |
| `notification`, `mail_outbox` | kind (REQUIRED/FYI), template_key, params jsonb (allowlisted), group_key, dedupe_key unique, read/cleared; outbox status/attempts/relay_msg_id |
| `audit_entry` | actor, role, action, outcome (SUCCESS/REFUSED/FAILED), system/submission/finding ids, correlation_id, detail jsonb (allowlisted keys); trigger forbids UPDATE/DELETE unless `annualva.retention='on'` |
| `export_job`, `disposal_list`, `disposal_item`, `scheduler_lock`, Spring Session JDBC tables | as named |

### 2.3 VA Submission state machine (commands in `submission`; each checks state → role → ownership → SoD → preconditions, records participation, audits, publishes an event)

| Command | From → To | Role | Step-up | Notes |
|---|---|---|---|---|
| `start` | none → DRAFT | SMO | no | allowed before the Quarter; windows snapshotted from `cycle` |
| `submit` | DRAFT/RETURNED → IN_REVIEW | SMO | no | every Finding proposed, no Target Date Missed pending, Secure Config slot READY, other slots READY/N/A/MANUAL; no file > 90 days (names the file), none future-dated or still scanning; declaration snapshot |
| `reviewFinding`/`reviewBatch`/`reviewSlot` | IN_REVIEW | CM assigned | no | CONFIRMED or DISPUTED + comment |
| `returnForRework` | IN_REVIEW → RETURNED | CM | no | ≥1 DISPUTED; confirmed stay confirmed; REQUIRED notification |
| `passToEndorsement` | IN_REVIEW → AWAITING | CM | no | all CONFIRMED; notifies both POCs (group_key) |
| `endorse` | AWAITING → ENDORSED | VA POC | **yes** | `endorsed_late = today > endorse_due`; clears the other POC's item |
| `returnFromEndorsement` | AWAITING → RETURNED | VA POC | **yes** | comment; chosen Findings become DISPUTED (as VA POC) |
| `requestExemption` | any non-final → EXEMPTION_REQUESTED | SMO | no | stores previous_state (none ⇒ NOT_STARTED row); PRIOR within 3 months before Quarter / SCHEDULED within 3 months after |
| `approve/rejectExemption` | ER → EXEMPTED / previous_state | VA POC | **yes** | conditional if SCHEDULED; `lapse_deadline = quarter end + 3 months` |
| `reportExemptionVaDone` / `confirmExemptionVaDone` | EXEMPTED conditional | SMO / VA POC | no / **yes** | lapse suspended while confirmation pending |
| `withdraw` | non-final → WITHDRAWN | Register apply | (apply step-up) | read-only; excluded from counts/reminders |
| `closeCycle` | manual or auto | VA POC / system | **yes** (manual) | all non-final in live Quarters → NOT_ENDORSED; Not Started Systems get a row |
| `moveQuarter`, `transfer` | Release 2 | Register apply | | schema ready in Release 1 |

Rejections: 409 `invalid-transition`, 403 `forbidden`/`sod-conflict`/`step-up-required` (all audited REFUSED), 404 for not-owned (same as missing).

**SoD:** `submission_participation` is written on first action per step; a command at step X is refused if the caller holds any other step on that submission. Stricter addition: the currently assigned SMO counts as SUBMIT and the assigned CM as REVIEW even before acting, so a VA POC who is the System's SMO can never endorse it. Register preview marks rows that would create a two-step person as **Blocked**.

**Scheduled jobs** (plain beans, `@Scheduled` only when `annualva.scheduling.enabled=true`; tests move the `Clock` and call them): `OverdueSweep` hourly; `CycleAutoClose`/`CycleAutoOpen` daily; `ConditionalExemptionLapse` daily (cycle open ⇒ previous state + new 2-week windows, endorse due = later of Quarter end and Cycle close; cycle closed ⇒ NOT_ENDORSED); `QuarterStartRoleReturn`, `RoleLapse` daily; `ReminderPlanner` daily 08:00 SGT; `ScanPoller` and `OutboxDispatcher` every 30 s; `ExportRunner`.

**Calendar:** Quarter n of Cycle Y starts the first Monday of Feb/May/Aug/Nov; ends the day before the next start (12–14 weeks); `submit_due = start+13d`, `review_due = start+27d`, `endorse_due = end`; a System added partway gets `added_on+13d` / `+27d`; preview suggests the next Quarter when < 28 days remain; `first_live_quarter` (Cycle 2027 = Q3) makes earlier Quarters "Handled outside the app". Property-based test over 2026–2035.

### 2.4 Finding Ledger rules

- **Identity** (per System + report type): Dependency-Check `dc|<CVE/GHSA>|<purl package or fileName>`; SCAP `scap|<rule idref>`; Nessus `nessus|<check id/name>`; Burp `burp|<type id>|<host>|<path>`. Severity 5 levels; unrated Dependency-Check → HIGH, unrated SCAP/Nessus → MEDIUM, scanner "Info" → INFORMATIONAL.
- **Occurrences** per host/component/file per `report_file`; Finding severity = max; `not_completed` when all occurrences did not complete (XCCDF error/unknown, Nessus ERROR). Pass/notapplicable/notchecked and Nessus non-compliance items ignored.
- **Replace file** compares only that file's occurrences: kept keys keep status/history; keys gone from every file ⇒ RESCAN `REMEDIATED` proposal (evidence names the rescan); new keys ⇒ new Findings; old file `superseded_by` → disposal list after endorsement.
- **Statuses** and required fields enforced by DB check + Bean Validation: Remediated→evidence; False Positive→justification; Risk Accepted→reason + approver name/role (+ optional memo file via pipeline); Remediation Planned→plan + future target_date. Free text ≤ 4000 chars, control chars stripped.
- **Bulk**: one `bulk_batch` + one proposal per Finding; CM reviews a batch as a whole (applies to PENDING members).
- **Manual** entries require `report_slot.mode=MANUAL` + justification, always marked. **Not applicable** allowed for Dependency-Check and Burp only, reviewed like a Finding; a DISPUTED slot blocks pass-to-endorsement.
- **Re-review only changed**: CONFIRMED holds only while `current_proposal_id == review.proposal_id`; any new proposal resets to PENDING. Rework queue order: DISPUTED → Target Date Missed → Carried Over pending (R2) → new Critical/High → new Medium/Low/Info.
- **Carry-over** (Release 2 behaviour, Release 1 schema): from the latest ENDORSED submission (CONFIRMED-only from NOT_ENDORSED), `proposed_as=CARRY_OVER`, `cycles_open` chain; Risk Accepted needs re-justification each Cycle.

### 2.5 Upload pipeline and scanner adapter

Stage order (tests assert it): request validation → **quarantine** to DIRTY store + sha-256 → **malware scan** (async) → **type allowlist** (magic bytes + root element / JSON shape vs declared slot) → **schema check** → **parse** → **pre-fill** scan date/target → **age** (future-dated rejected now; > 90 days enforced at submit). `pipeline_status`: QUARANTINED → SCAN_SUBMITTED → SCANNING → SCAN_CLEAN → TYPE_OK → SCHEMA_OK → PARSED → READY, or REJECTED_{MALWARE (quarantined bytes are discarded: malware is never retained, a decision recorded beside the retention rules), SCAN_TIMEOUT, TYPE, SCHEMA, UNREADABLE, FUTURE_DATED, HASH_MISMATCH}. Upload returns 202; client polls file status; bytes never served before SCAN_CLEAN.

`MalwareScanner`: `submit(dirtyRef, displayName, mime) → ticket`, `check(ticket) → PROCESSING|CLEAN_UNCHANGED|CLEAN_SANITIZED|BLOCKED|ERROR`, `fetchClean(ticket)`. SFS adapter: POST /put → PUT presigned → POST /get until Allowed/Blocked → GET clean → hash check (Unchanged must equal upload hash). `ScanPoller`: ≤ 10 in flight, `scheduler_lock` + `SKIP LOCKED`, attempt timeout (prod 10 min, dev 2 min, ci set by test), **3 attempts total** (= spec "retried twice") then REJECTED_SCAN_TIMEOUT with the story-27 message. Never log presigned URLs or file names. No `scanner.enabled=false` bypass: dev-mcc uses mock-SFS keyword verdicts (`_quarantined` in display name), ci uses the fake.

Formats and limits (ADR, human confirms): DC XML/JSON 25 MB; XCCDF results 25 MB; ARF 50 MB (UI hint to export XCCDF results only); `.nessus` 50 MB; Burp XML 50 MB (hint: export without request/response bodies); evidence PDF 20 MB, HTML 10 MB; Register xlsx 2 MB; multipart ceiling 50 MB; ≤ 20 files per slot. XML via StAX with secure processing, DTD/external entities off, XInclude off; a DOCTYPE is skipped and never obeyed (DTD and entities stay off for every format); Burp's internal subset is accepted only as Burp writes it (ELEMENT and ATTLIST declarations, no external id; see ADR 0025). Roots: DC `analysis`, ARF `asset-report-collection`, XCCDF `Benchmark`/`TestResult`, Nessus `NessusClientData_v2`, Burp `issues`; DC JSON needs `reportSchema`, `scanInfo`, `dependencies[]`. Scan dates: DC `projectInfo/reportDate`, XCCDF `TestResult@end-time`, Nessus max `HOST_END`, Burp `issues@exportTime` (flagged export-time-only).

### 2.6 Access

- **Sign-in**: `oauth2Login` registration `mcc-sso-login` (client `app-appfw`), PKCE S256, `private_key_jwt` ES512 with the dev EC P-521 key (set `alg` explicitly; kid `mcc-dev-ec521`), scope `openid afcas`, `kc_idp_hint=singpass`, explicit endpoints (never `issuer-uri`) + SSL bundle trusting `../mock-m-sso/keycloak/certs/mock-oidc.crt`; `/.well-known/jwks.json` permitAll. Identity: `afcas.uuid` (nested or flat; missing ⇒ login refused), email claim. Linking: by `sso_id`, else by email where `sso_id` null, else create user with no roles (shows "Unlinked SMO" resolution). Require `auth_time` (fail closed; the fake issuer sends it) and `amr` ∋ `mfa` where the IdP provides it.
- **Session**: Spring Session JDBC, id rotated at login, tokens discarded, idle **30 min** (spec as-11; stricter 15 min from the standard recorded as a deviation), absolute 8 h, one concurrent session, `POST /api/logout` + `Clear-Site-Data`. CSRF: **session-based synchronizer token** bootstrapped by `GET /api/csrf` (cookie/double-submit is prohibited by the org standard).
- **Authorisation**: `anyRequest().authenticated()` + `@PreAuthorize` on every controller method (ArchUnit) + `AccessGuard.requireSystem(systemId, need)` in every service path including file and export downloads; not-owned ⇒ identical 404. Authorities resolved **per request** from `role_grant` so removal is immediate; `last_used_at` touched once per day.
- **Roles**: VA_POC from `annualva.va-pocs` (exactly two emails, startup check, `source=CONFIG`); SMO/CM from Register apply (`source=REGISTER`, per System); AUDITOR granted by VA POC with step-up, `expires_at` ≤ 90 days, scope rows (Release 2). Nobody can grant to their own user/email.
- **Lapse/removal (ac-3/ac-4)**: daily lapse at 90 days unused; returns via Register naming, Quarter start, or hand-off events; immediate revoke when no longer named or IdP-disabled; role-holder review endpoint with per-System suspend (step-up), review audited.
- **Step-up**: `StepUp.requireFresh(intent)` passes if session `auth_time` ≤ 5 min old; else **403** ProblemDetail `type=…/step-up-required` with `stepUpPath`. `/auth/step-up` stores intent + return URL and starts a fresh authorization request (`prompt=login`, `max_age=0`); callback verifies `auth_time` and refreshes the session marker without extending the session. If MCC SSO ignores `prompt`, the same component forces a full re-login (assumption A-5). Step-up list: endorse; VA POC return; approve/reject Exemption; confirm conditional VA; Register apply; Auditor grant/revoke; role suspend; disposal approval; close Cycle early; management summary / auditor pack / CM detailed export.

### 2.7 Audit, logging, notifications

- **Audit actions** (string enum): SIGN_IN/OUT/FAILED, STEP_UP_OK/FAILED, REQUEST_REFUSED, REGISTER_TEMPLATE_DOWNLOADED, REGISTER_PREVIEWED/APPLIED, SYSTEM_RETIRED/MOVED/TRANSFERRED, ROLE_GRANTED/REVOKED/LAPSED/SUSPENDED/REVIEWED, SUBMISSION_STARTED/SUBMITTED/RETURNED/PASSED/ENDORSED/WITHDRAWN/NOT_ENDORSED/CYCLE_CLOSED, FILE_UPLOADED/REJECTED/REPLACED/VIEWED/DOWNLOADED, FINDINGS_VIEWED (one entry per list request with ids), STATUS_PROPOSED, BATCH_PROPOSED, FINDING_CONFIRMED/DISPUTED, BATCH_REVIEWED, EXEMPTION_*, NOTIFICATION_SENT/BOUNCED, EXPORT_REQUESTED/DOWNLOADED, DISPOSAL_LISTED/APPROVED/EXECUTED. `detail` jsonb allowlisted (state_from/to, batch_id, count, file_id, reject_code); never CVE ids, rule names, hosts, comments or justifications (lm-19). Reads: VA POC all; CM only submissions they reviewed on Systems still assigned; Auditor within scope.
- **Logging**: `logging.structured.format.console=ecs`; MDC `trace.id` (from well-formed `X-Correlation-Id` or generated; echoed in header and `ProblemDetail.traceId`), `user.id` (app_user uuid, never email); fluent API only; separate audit appender; ArchUnit bans `System.out`, `printStackTrace`, static `now()` outside `BusinessTime`. Sentinel test: a sample containing `CVE-9999-SENTINEL` never appears in captured logs.
- **Notifications**: `Notifier` on after-commit events → `notification` rows (POC items as two rows sharing `group_key`) + `mail_outbox` for REQUIRED kinds (FYI in-app only in Release 1; digest Release 2). Reminders: SMO at `submit_due−5d`, `submit_due`, weekly while Overdue (CM gets a separate cc message; MCNS has no cc); CM at `review_due−5d`, `review_due`, weekly (POCs cc); POCs at `endorse_due−14d`, `−7d`. `dedupe_key` makes planning idempotent. Pair clearing: a POC decision sets `cleared_by` on the sibling row ("handled by (name)"). `OutboxDispatcher` → `MailRelay.send` → `ACCEPTED|REJECTED_ADDRESS|FAILED`; REJECTED_ADDRESS sets `smo_unreachable` (story 13). MCNS adapter: logical template key → configured template id; `templateValues` allowlist {systemName, state, action, dueDate, link, changeKind}; Resilience4j rate limiter + retry inside the adapter. **Gap**: MCNS gives no delivery/bounce feedback, so true bounces are undetectable; compensating weekly digest of Unlinked/Unreachable/never-signed-in SMOs to the POCs; PO acknowledgement needed.

### 2.8 Exports and retention

- `POST /api/exports` (type, format, params; step-up) → `export_job` run by `ExportRunner` on virtual threads → stored via `FileStore` (clean bucket `exports/`, 24 h expiry) → `GET /api/exports/{id}` status → `GET …/download` (`Content-Disposition: attachment`, audited). xlsx via POI `SXSSFWorkbook` with classification banner row/header; PDF via **OpenPDF** with diagonal watermark on every page. Classification CONFIDENTIAL when covering more than one System or a whole Quarter, else RESTRICTED. Management summary DTO has no fields for vulnerability detail.
- Retention: `computeDue(today)` = Cycle `closes_on + 3 years` (submissions, files, findings, declarations, notifications, audit rows of that cycle) + superseded files once ENDORSED + expired exports → `disposal_list DRAFT` → VA POC `approve` (step-up) → executor deletes objects then rows (`SET LOCAL annualva.retention='on'`) → tombstone audit entry. Any earlier removal is soft (`deleted_at`).

---

## 3. Frontend architecture (lighter stack)

- `package.json` scripts: `dev`, `build` (`tsc -b && vite build`), `typecheck`, `format`/`format:check` (Prettier), `audit:code` (`fallow dead-code && fallow health`, whole project: `fallow audit` needs a git base branch, and its CRAP score needs coverage data this project does not produce, so the CRAP rule is off in `.fallowrc.jsonc`), `arch:check` (`depcruise src --config .dependency-cruiser.cjs`), `lint:no-raw-html` (fails on `dangerouslySetInnerHTML`/`innerHTML`), `check` (all gates). Deps: `react`, `react-dom`; dev: `typescript`, `vite`, `@vitejs/plugin-react`, `@vitejs/plugin-basic-ssl`, types, `prettier`, `fallow`, `dependency-cruiser`. Nothing else (depcruise rule).
- `vite.config.ts`: `basicSsl()`, port 5173 strict, proxy `/api`, `/oauth2`, `/login`, `/logout`, `/auth`, `/.well-known` → `https://localhost:8443` with `secure:false`, `changeOrigin:false`, `xfwd:true`; backend `server.forward-headers-strategy: framework` so cookies/redirects stay on 5173.
- `tsconfig`: strict, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noUnusedLocals/Parameters`, `verbatimModuleSyntax`, `noEmit`, bundler resolution.
- Layout: `src/main.tsx`, `styles.css` (CSS variables from the prototypes, light/dark); `src/app/` (App session state machine, Shell with ClassificationBanner + role nav + CycleSelector, `router.ts` ~40 lines: `parse(pathname) → Route` union + `navigate()` via `history.pushState` + `useRoute()`, StepUpInterstitial, SignedOutScreen, ErrorBoundary, idle timer); `src/api/` (`client.ts` + one module per resource); `src/hooks/` (`useResource`, `useAction` with in-flight + step-up intercept, `useSelection`, `useKeyboardShortcuts`); `src/components/` (ClassificationBanner, ClassifiedField, FilterChips, BulkBar, ProgressBar, StateBadge, SeverityBadge, DataTable, ErrorPanel, FileDrop, Tabs, Dialog, Button, EmptyState); `src/screens/{smo,cyber-manager,va-poc,auditor,shared}/`.
- `api/client.ts`: one `request<T>()`, same-origin credentials, CSRF header from `GET /api/csrf` (cached, refreshed on CSRF 403), `application/problem+json` → `ApiError{type,title,status,detail,instance,traceId}`; 401 → signed-out; 403 `step-up-required` → save `{route, draft}` in `sessionStorage`, go to `/step-up?return=…`, user re-triggers the action afterwards (never auto-replayed); downloads via `<a download>`/`window.location`, never fetch+blob; multipart via `FormData`.
- Session: `GET /api/session` → `{authenticated, uuid, name, email, roles[], classification, idleTimeoutSeconds, absoluteExpiresAt, cycles}`; "Still there?" dialog at 28 min; visibility-aware check for the 8 h limit; sign-out = CSRF + `POST /api/logout`.
- Non-negotiable UI rules: report content only as React text nodes; ClassificationBanner on every screen incl. signed-out/step-up/error; every input via ClassifiedField (label + classification tag + `aria-describedby`); submit buttons disabled while in flight; ErrorPanel shows title/detail + "Show reference" (traceId), `role="alert"`; WCAG 2.1 AA basics (skip link, h1 focus on route change, `<th scope>`, `aria-live` on progress, focus-trapped dialogs, shortcuts `1–4`/`Enter` in the rework queue ignored while typing).
- depcruise rules (error): no-circular, no-orphans, no HTTP client libs, only `src/api/**` touches `client.ts`, components never import api/screens, no cross-role screen imports, only `src/app/**` mounts screens, api has no UI imports.

### 3.1 Screen inventory (R = Release)

| Role | Screen / tab | Resources | R |
|---|---|---|---|
| All | Shell (banner, Cycle · Quarter · week, role nav, Cycle selector), signed-out, step-up, not-found, notifications list | session, cycles, notifications | 1 |
| SMO | Inbox (Needs your action / Waiting on others / Done, Overdue pills, Start/Continue) | dashboards/smo-inbox, submissions:start | 1 |
| SMO | Submission prepare: 3 Scan Report cards (files, check results, scan date + target ClassifiedFields, Replace, N/A + justification for DC/Burp), manual-entry mode, spreadsheet with FilterChips (Needs attention, All, Disputed, Critical/High; Carried Over + Target Date Missed R2), tick/select-all, sticky BulkBar, per-row status + required field, ProgressBar, Declaration + Submit (disabled until complete; stale/future file message names the file) | submissions, scan-reports, files, findings (propose, bulk, manual), declaration-text, submissions:submit | 1 |
| SMO | Rework guided queue (Disputed → … one at a time, Dispute comment, `1–4`/`Enter`, Resubmit) | submissions/rework-queue, findings:propose, submissions:submit | 1 |
| SMO | Exemption request; record VA happened; read-only submission after submit | exemptions, submissions | 1 |
| CM | Inbox (Overdue first, SoD-blocked badge) + Quarter board tab | dashboards/cm-inbox, quarter-board | 1 |
| CM | Review: cards read-only, batches grouped with Confirm/Dispute batch, per-Finding Confirm/Dispute + comment, Manual/N/A marked, "changed since last review" filter, Return for rework, Pass to endorsement | findings (confirm, dispute, batches), submissions:return, :pass-to-endorsement | 1 |
| CM | Audit history of reviewed submissions; detailed export | audit-log, exports | 1 / 2 |
| VA POC | Quarter board tab; Cycle matrix tab (4-Quarter strip with stacked state bars, one row per System with milestone dots) | dashboards/quarter-board, cycle-matrix | 1 |
| VA POC | Exemption requests waiting (approve/reject/confirm VA, "handled by"); endorsement view (Endorse/Return, Endorsed Late, SoD notice) | exemptions, submissions:endorse/:return | 1 |
| VA POC | Register: template download, upload (step-up on apply), preview groups (Will withdraw R1; Will move/transfer/Blocked R2), confirm retirements, Apply; Register table with Unlinked/Unreachable flags; first-live-Quarter setting | system-register, systems | 1 (+2) |
| VA POC | Role-holder review (suspend, step-up); Auditor grant (R2); Disposal approval; Cycle close; Exports (management summary xlsx/pdf; auditor pack R2) | role-holders, disposal-lists, cycles:close, exports | 1 / 2 |
| POC/CM/Auditor | Audit log viewer (filters, paging, no Finding detail) | audit-log | 1 |
| Auditor | Read-only browse within scope; evidence pack | submissions, findings, audit-log, exports | 2 |

---

## 4. Local platform, profiles, keys

- **compose.yml**: the six `mock-m-sso` service blocks (localstack, keycloak, mockpass, mock-mpds, mock-mcns, mock-sfs) copied **verbatim** from the bootstrap standard (compatibility pins), plus project-owned `postgres:17-alpine` (db/user `annualva`, mock password, `pg_isready` healthcheck, named volume). `compose.override.yml.example` for corporate-proxy CA injection into the `maven:3` mock containers.
- **scripts/localstack-init/**: `01-kms-key.sh` (symmetric key + `alias/annualva-dev`), `02-buckets.sh` (`annualva-quarantine-dev`, `annualva-clean-dev`, default SSE-KMS with that alias, deny unencrypted puts). Executable bit + LF endings.
- **Profiles**: `application.yml` = production shape (`${VAR}` keys only: datasource, OIDC endpoints, S3/KMS, SFS, MCNS, `annualva.va-pocs`, `annualva.time-zone`; literals only for port 8443, actuator `health,info`, forward-headers, session timeout 30m, virtual threads on). `application-dev-mcc.yml` = mock literals (explicit Keycloak endpoints, `app-appfw`/`svc-appfw`, base64 EC key, SSL bundle `mcc-trust` → `file:../../mock-m-sso/keycloak/certs/mock-oidc.crt` relative to `backend/`, `dev-keystore.p12`, LocalStack `http://localhost:4566`, Postgres `localhost:5432`, dev identity map). `application-ci.yml` (test classpath) = Testcontainers + fakes, `server.ssl.enabled=false`, non-secure cookie, scheduling off. `application-sit.yml` + `.env.sit.example` + `ProfileDotenvPostProcessor` (write it; no reference implementation exists) arrive with the first SIT-bound ticket.
- **Keys** (`scripts/gen-dev-keys.sh`, Git Bash, JDK 21 `keytool`): `keytool -genkeypair -alias mcc-dev -keyalg EC -groupname secp521r1 -sigalg SHA512withECDSA -keystore backend/src/main/resources/dev-keystore.p12 -storetype PKCS12 -storepass changeit -validity 3650 -dname "CN=localhost" -ext "SAN=dns:localhost,dns:host.internal,ip:127.0.0.1"`; signing key `openssl ecparam -genkey -name secp521r1 -noout | openssl pkcs8 -topk8 -nocrypt` → base64 into `application-dev-mcc.yml` (PEMs never committed). Keycloak fetches the JWKS from `host.internal:8443`, so a fresh key works; verify the `app-appfw` client's redirect URIs allow `https://localhost:5173/login/oauth2/code/*` when `mock-m-sso` arrives.
- **Dev identities**: MockPass personas carry a `uuid` but no email; the Register works by email. `annualva.dev.identities: {<uuid>: email}` in `dev-mcc` feeds a `@Profile("dev-mcc")` resolver used only when the token has no `email` claim. Six personas: `smo.a/b`, `cm.a/b`, `poc.1/2@dev.annualva.test`; `scripts/dev-data/system-register.sample.xlsx` assigns Systems accordingly (never the same person as SMO and CM). `scripts/mock-mockpass-assertions.js` pins the uuids.
- **Mock verdicts**: mock-SFS keyword in display name (`unchanged`/`sanitized`/`quarantined`); mock-MCNS mail visible via `docker compose logs -f mock-mcns | grep "Event=email"`.
- **`.claude/launch.json`**: `frontend` (`npm run dev --prefix frontend`, 5173, https) and `backend` (`backend/mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev-mcc`, 8443, https).

---

## 5. Testing and quality gates

- **Seam 1 (primary)**: `AbstractApiTest` = `@SpringBootTest(DEFINED_PORT)` with free port, `@ActiveProfiles("ci")`, one shared context, singleton reusable Testcontainers `postgres:17`, Flyway for real. Fakes on the test classpath (`harness/`, `@Primary`): `FakeOidcIssuer` (real signed id_tokens with `auth_time`; knobs for identity, stale `auth_time`, records `prompt=login`), `FakeScanner` (clean-unchanged / sanitized / blocked / never-finishes), `CapturingMailRelay` (+ `rejectAddress`), `InMemoryFileStore`, `MutableClock`. Helpers: `Actors.smo(..)/cyberManager(..)/vaPoc(1|2)/auditor(..) → ApiSession` (cookie jar, CSRF, `stepUp()`), `Timeline.at(cycle, quarter, week, day)` (09:00 SGT), `Jobs.*()` call job beans, `RegisterXlsx`, `Samples.*` from `src/test/resources/samples/<format>/` each with `PROVENANCE.md` (schema-faithful reconstructions naming only throwaway targets; regenerate from real scans before release). Assertions only on observable outputs. Required coverage areas: every state-table row incl. rejected transitions; SoD across both POCs and Register changes; every upload check in order; re-upload matching; every preview grouping; export content/watermark/no detail; audit entry per action; access denials incl. direct download by non-owner. Permission matrix as one parameterised CSV test (~150 rows + 26 step-up + ~12 SoD), target < 3 min; full suite < 8 min.
- **ArchUnit** (`src/test/java/sg/annualva/arch/`): module dependency table (§2.1), no cycles, `boundary` implemented only in `adapters..` on main, controllers/web never touch `JdbcClient` or `*Store`, `JdbcClient.sql` only in `*Store` with constant strings (+ `-XDstringConcat=inline` so concatenation is visible), no `System.out`/`printStackTrace`/JUL, XML factories only in `parser.xml.SecureXml`, every `@RestController` method `@PreAuthorize` or in the permitAll list, `@Scheduled` only in `*Job`, no `*Fake*|*Stub*|*InMemory*` on main, no static `now()`.
- **Gates**: backend `./mvnw -B -ntp clean verify` (JaCoCo BUNDLE LINE+BRANCH ≥ 0.80, excludes only `Application` and generated code); frontend `npm run check`; `/code-reviewer` per ticket → `artifacts/code-reviewer/NN-slug-compliance.html` (semgrep via `semgrep/semgrep` Docker image); Conventional Commits with the session's `Co-Authored-By` trailer (the git-commit-skill's "no trailer" rule is overridden by the session instruction).
- **Seam 2 (late Release 1)**: `e2e/` per the e2e skill (own `package.json`: `@playwright/test`, `allure@3`, `allure-playwright`, `@playwright/mcp` exact; `e2e/README.md` profile; fixtures for the six personas via the MockPass picker; `e2e-foundation.json` manifest; `scripts/e2e-stack.ps1 up|down|reset` with a `dev-mcc`-only reset script; `artifacts/e2e/` gitignored). Scenarios: S1 SMO spreadsheet + bulk-set + Submit enabling (36–38, 40); S2 rework queue + shortcuts (60–62); S3 dashboard tabs per role (18, 48, 49, 64, 45); S4 management summary watermarked, no detail (81, 86); S5 optional Register preview → apply (2, 5).

---

## 6. Governance

### 6.1 IM8 control ownership (condensed)

| Control | Mechanism | Owner slice |
|---|---|---|
| ac-1, as-7 | deny-by-default + `@PreAuthorize` everywhere + `AccessGuard` ownership incl. downloads; ArchUnit + permission-matrix test | S01 (deny-all), S03 |
| ac-2 | MFA claims + fail-closed `auth_time`; step-up component + list; fake issuer simulates | S02, S08 |
| ac-3, ac-4 | lapse job, immediate revoke on Register apply, hand-off returns; role-holder review + suspend | S09, S43 |
| ac-7, ac-12, ac-8 | single OIDC registration, no local credentials; JIT linking; Auditor expiry | S02, S03 |
| as-1 | allowlist + root-element sniff + per-type size table + Bean Validation on every free-text field | S12 |
| as-2 | `JdbcClient` named params, constant SQL; ArchUnit + semgrep | S01 |
| as-3, as-9 | JSON-only API; React text nodes; `lint:no-raw-html`; CSP `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'` + standard headers (nosniff, X-Frame-Options DENY, Referrer-Policy, Permissions-Policy, COOP) | S01 |
| as-10, as-11 | HSTS 1 year includeSubDomains; 30 min idle / 8 h absolute / 1 session / Clear-Site-Data | S01, S02 |
| as-12, as-13, as-14 | scan before read; RFC 9457 with fixed types, `include-*=never`; JDK/Spring/AWS crypto only | S12, S01 |
| ck-1, ck-2, ck-4, dp-3 | S3 SSE-KMS with CMK (yearly rotation, key policy = app role only, CloudTrail); Postgres encryption platform-owned; TLS 1.2+ everywhere, `sslmode=verify-full` outside ci, no trust-all (ArchUnit/semgrep) | S46 + H3 |
| dp-8 | ClassificationBanner + ClassifiedField; `/api/session.classification` | S01 shell, every UI slice |
| lm-4, lm-15, lm-19 | audit every action + refused requests; ECS JSON, MDC trace/user ids, separate audit appender; ids only, sentinel test | S01, S04, S12 |
| lm-16, pm-6 | actuator + Micrometer meters (overdue, scan failures, mail failures, step-up refusals, audit-write failures); README, ADRs, `/tech-arch-doc`, threat model, `/pre-prod-check` | S47, H |

### 6.2 ADRs

Write at scaffold (S01): 0001 PostgreSQL 17 + `JdbcClient` + Flyway over JPA/Liquibase · 0002 flat package-per-module instead of the bootstrap `shared/features` split, ArchUnit-enforced · 0003 `mock-m-sso` as local platform, Seam-1 fakes as the test boundary, no interim stand-in unless H0 slips past S02 · 0004 step-up = fresh OIDC authentication (`prompt=login`, `max_age=0`, 5-min freshness, fail closed; full re-login fallback) · 0005 Spring profile contract (base = production shape; dev-mcc; ci; sit later with dotenv post-processor) · 0006 business time zone `Asia/Singapore`, UTC storage · 0007 HTTPS 8443 in dev with `dev-keystore.p12`; plain HTTP in ci · 0008 idle 30 min per spec (deviation from the 15-min standard) + 1 concurrent session · 0009 no React unit-test seam · 0010 UUID primary keys · 0011 authorities resolved per request; Spring Session JDBC · 0012 DB `scheduler_lock` + `SKIP LOCKED` instead of ShedLock.
Write when the slice lands: 0013 SFS polling semantics (3 attempts, Sanitized accepted with clean hash [superseded 2026-10-13: Sanitized is rejected, ADR 0021 decision 5], Unchanged must match, Blocked rejects, no dev bypass) · 0014 bounce handling under MCNS (synchronous rejection only) · 0015 encryption at rest via S3 SSE-KMS CMK (app-side envelope rejected) · 0016 OpenPDF for watermarked PDFs · 0017 five-level severity and unrated mapping · 0018 per-type upload size limits · 0019 Burp internal DTD subset allowed · 0020 dashboard read models may own one cross-module SQL view · 0021 Not Started persisted only when created by an Exemption request.

### 6.3 Human prerequisites (ready-for-human tickets)

| # | Human does | Agent meanwhile | Needed by |
|---|---|---|---|
| H0 | Obtain `mock-m-sso` (ask SGTechStack / App-Standards owners; clone to `D:\projects\mock-m-sso`; verify realm client redirect URIs + JWKS URL) | S01–S05 (Seam 1 only) | end of S02 for browser demo |
| H1 | Declaration text + version owner | placeholder text clearly marked | S23 |
| H2 | Confirm A-1..A-9 with cyber team (A-1→86/dp-8; A-2→hosting/ck; A-3→24/27; A-4→72; A-5→77/87; A-6→73–76/80a; A-7→linking; A-8→retention; A-9→88–94) | build behind ports/config | before S09 |
| H3 | Products + NFRs: managed Postgres, S3, KMS CMK, SFS, MCNS, hosting tier; availability/backup/DR/browser/a11y targets; approve size limits | adapters vs mocks | before S46 |
| H4 | MCC SSO onboarding via MTM (client ids, keys, redirect URI, `kc_idp_hint`, `afcas.uuid` mapper, `prompt=login`/`auth_time` behaviour) | fake issuer + mock stack | SIT entry |
| H5 | MCNS template onboarding (reminder-due-soon, reminder-due, overdue-weekly, cc variants, returned-for-rework, disputed, register-change, exemption-decision, endorsed-fyi, weekly-digest) with allowed keys | template-key config + mock | SIT entry |
| H6 | Real sample exports from throwaway targets (DC with NVD key, CIS-CAT/OpenSCAP, Nessus, Burp) | reconstructions with provenance notes | before UAT |
| H7 | Pentest booking (March 2027), UAT plan, env provisioning (IAM role, key/bucket policies, DB TLS), VA POC names, corporate proxy CA install | threat model draft, `.env.sit.example`, `/wizard` scripts | per milestone |

### 6.4 Top risks

| Risk | Mitigation |
|---|---|
| `mock-m-sso` unavailable (no URL) | H0 on day 1; Seam 1 unaffected; if H0 slips past S02, add a `dev-standin` profile with `mock-oauth2-server` + in-memory scanner/mail as a documented exception, removed by the integration ticket |
| MCC SSO ignores `prompt=login`/`max_age` | same `StepUp` component falls back to forced full re-login; verify at SIT; app-OTP via MCNS kept as Plan B |
| MCNS has no bounce feedback (story 13) | synchronous rejection → Unreachable; compensating digest; PO decision |
| Story 50 critical path (~60 days) | split into S26 (SoD + single review), S27 (batch), S28 (return/pass), S30 (re-review changed) |
| Build must start by early Nov 2026 | scaffold + S01–S05 need no answers from H2/H3 |
| Quarter length 12–14 weeks; two-Cycle overlap; Q4 conditional exemptions after close | calendar rules in §2.3, property-based tests, explicit single post-close transition |
| 80 % coverage on an integration suite; Testcontainers speed | one shared context, reusable container, deliberate JaCoCo excludes recorded |
| Sample exports are reconstructions; real reports are live target lists | provenance notes, throwaway targets only, pre-commit grep for real hostnames, H6 before UAT |
| Spring Boot 4.0.x churn; Java 25 drift | pin minor; Java 21 LTS recorded; `spring-*-review` skills per slice |

### 6.5 Questions for the PO (raise via tickets marked needs-info)

Release 1 handling of Will move and Will transfer Register rows (classified, blocked until the Release 2 mid-cycle tickets) · Bounce semantics under MCNS · step-up mechanism and 5-min freshness · "every view" audit granularity for list endpoints (one entry per request with ids) · watermark wording per classification and whether a multi-System auditor pack is the "whole set" · Auditor scope granularity and default expiry · Overdue/reminders for Unlinked/Unreachable SMOs · step-up for Auditor-run packs and CM detailed export · size limits · 30-min idle vs 15 · 1 concurrent session · Informational severity in chips · rejecting SFS-sanitized machine-readable reports · story 50 split · audit entries never disposed early.

---

## 7. Build order: the Release 1 ticket set

The tickets live in `.scratch/annual-va/issues/NN-slug.md`, numbered in dependency order (blockers first). Each agent ticket is demoable through the API with Seam 1 tests; H marks ready-for-human tickets. The set was vetted before publishing (every Release 1 story covered, no forward blockers, Release 2 behaviour kept out), which split the original slices for the VA POC board and endorsement, the role lapse and the role-holder review, and added the audit and state-table completeness ticket.

| # | Ticket | Stories | Blocked by | Status |
|---|---|---|---|---|
| 01 | Obtain the mock-m-sso stack and prepare the developer machine | none (prerequisite) | none | H |
| 02 | Walking skeleton: repository, security baseline and Seam 1 harness | none (foundation) | none | closed |
| 03 | Sign in through MCC SSO and see my session | 72 | 02 | agent |
| 04 | Roles held in the app and every endpoint denied by default | 75, 76, 78 | 03 | agent |
| 05 | Append-only audit log with refused-request logging | 84 (part) | 04 | agent |
| 06 | VA Cycle and Quarter calendar | 16, 71 (dates) | 02 | agent |
| 07 | Download the System Register template | 1 | 04 | agent |
| 08 | Upload the System Register and preview every change | 2, 12, 14 | 07, 06 | agent |
| 09 | Step-up: a fresh sign-in before sensitive actions | 77 | 03 | agent |
| 10 | Apply a Register preview; SMO and Cyber Manager roles follow the Register | 2, 3, 4, 73, 79 | 08, 09, 05 | agent |
| 11 | SMO inbox: my Systems and what to do by when | 18, 71 (Not Started) | 10 | agent |
| 12 | Start a VA Submission | 19 | 11 | agent |
| 13 | Scan Report upload pipeline: quarantine, scan, check, store | 20 (pipeline), 24, 27, 78 | 12 | agent |
| 14 | Dependency-Check parser (XML and JSON) | 20a, 21, 25, 28 | 13 | agent |
| 15 | Secure Configuration parser: SCAP ARF and XCCDF results | 20b, 21, 25, 28, 29 | 13 | agent |
| 16 | Secure Configuration parser: Nessus compliance audit | 20c, 21, 25, 28, 29 | 13 | agent |
| 17 | Burp Suite parser: XML issue export | 20d, 21, 25, 28, 30 | 13 | agent |
| 18 | Attach PDF or HTML reports as supporting evidence | 22 | 13 | agent |
| 19 | List Findings and propose a Finding Status | 28, 35 | 14, 18 | agent |
| 20 | Filter chips, select-all, bulk bar and progress bar | 36, 37, 38 | 19 | agent |
| 21 | Replace a Scan Report file without losing work | 39 | 19 | agent |
| 22 | Mark a Scan Report not applicable or enter Findings by hand | 23 | 19 | agent |
| 23 | Confirm scan date and target; enforce the age rules | 25, 26 | 14 | agent |
| 24 | Agree the Declaration text and its owner | 41 | none | H |
| 25 | Tick the Declaration and submit for review | 40, 41 | 20, 22, 23, 15, 24 | agent |
| 26 | Overdue is a flag: soft windows, nothing locks | 42, 71 | 25 | agent |
| 27 | Cyber Manager inbox and Quarter board | 48, 49 | 26 | agent |
| 28 | Review engine 1: segregation of duties and single confirm or Dispute | 50 (part), 58 | 27 | agent |
| 29 | Review engine 2: confirm or Dispute a Bulk-set batch | 51, 52 | 28 | agent |
| 30 | Return for rework or pass to endorsement | 55, 57 | 28 | agent |
| 31 | Guided rework queue with Dispute comments and keyboard shortcuts | 60, 61, 62 | 30 | agent |
| 32 | Review engine 3: re-review only what changed | 56 | 30 | agent |
| 33 | VA POC Quarter board and Cycle matrix | 64 | 27 | agent |
| 34 | Endorse a VA Submission | 65, 67, 70 | 30, 09 | agent |
| 35 | VA POC return with comments; resubmission goes through the Cyber Manager | 66, 63 | 34, 32 | agent |
| 36 | In-app notifications and clearing between the two VA POCs | 92, 69 | 34 | agent |
| 37 | Email through the mail relay with the content rules | 91, 94, 88 to 90 (plumbing) | 36 | agent |
| 38 | Deadline reminders for SMOs, Cyber Managers and VA POCs | 88, 89, 90 | 37, 26 | agent |
| 39 | A rejected address marks the System SMO Unreachable | 13 | 37 | agent |
| 40 | Request, freeze and decide an Exemption | 43, 44, 45, 46 | 34, 36 | agent |
| 41 | VA Cycle lifecycle: open, overlap, close | 17, 68 | 34, 06 | agent |
| 42 | Conditional Exemption: record the VA, confirm it, or lapse | 47 | 40, 26, 41 | agent |
| 43 | Register preview effects on in-flight work: withdraw, and block the rest | 5, 6, 7 | 10, 12, 36 | agent |
| 44 | Management summary export (Excel), watermarked, no vulnerability detail | 81, 86 | 34, 33 | agent |
| 45 | Management summary export (PDF) with a diagonal watermark | 81, 86 | 44 | agent |
| 46 | Roles lapse after 90 days and return automatically | 80a (lapse), ac-3 | 10, 30, 42 | agent |
| 47 | Role-holder review, suspension and identity-provider disablement | 80a (removal), ac-3, ac-4 | 46 | agent |
| 48 | Cyber Manager audit history | 85 | 28, 05 | agent |
| 49 | Retention: disposal list, approval and hard delete | 87 | 44, 21 | agent |
| 50 | Completeness: every audited action and every row of the state table | 84, state table, Testing Decisions | 40, 41, 42, 43, 44, 47, 49 | agent |
| 51 | Production adapters: S3 with SSE-KMS, SFS scanner, MCNS mail, MCC service client | none (adapters) | 13, 37, 01 | agent |
| 52 | Key-signal monitoring and system documentation | lm-16, pm-6 | 51 | agent |
| 53 | Seam 2: Playwright foundation and the acceptance scenarios | 36 to 38, 40, 60 to 62, 18, 48, 49, 64, 45, 81, 86 | 20, 31, 33, 34, 40, 44, 01 | agent |
| 54 | Confirm assumptions A-1 to A-9 with the cyber team | assumptions A-1 to A-9 | none | H |
| 55 | Choose platform products and non-functional targets | open questions for the dev team | none | H |
| 56 | Onboard the application to MCC SSO through MTM | 72, 77 (SIT) | 03, 09 | H |
| 57 | Onboard the notification templates to MCNS | 88 to 94 (SIT) | 37 | H |
| 58 | Produce real sample exports from throwaway targets | 20a to 20d | 14, 15, 16, 17 | H |
| 59 | Pre-go-live milestones: pentest booking, UAT plan, environments, VA POC names | releases and timeline | none | H |

Release 2 (the schema already allows it; tickets are generated later and numbered on): carry-over 31 to 34, 53, 54; mid-cycle moves and transfers 8 to 11, 15, 80 (unblocks the Will move and Will transfer rows); Auditor role and pack 74, 82, 83; weekly digest 93; Cyber Manager detailed export 59.

---

## 8. Implementation step 1 (done 2026-10-04 and 2026-10-05)

1. **Scaffold** (commit `ea72f69`): repo layout, `compose.yml`, LocalStack init scripts, `scripts/gen-dev-keys.sh`, MockPass personas, `.claude/launch.json`, README, AGENTS.
2. **Docs** (`80d0602`): CONTEXT.md, ADRs 0001 to 0012, reviewer decisions, agent conventions, the spec and the structured stories.
3. **Backend skeleton** (`e161235`): section 2 for `app`, `config`, `web` and `boundary`; the Seam 1 harness with Testcontainers and the mutable clock; ArchUnit; the JaCoCo gate. Three fixes on the way: tests name the application class explicitly because it sits in `sg.annualva.app`; the harness clock bean must not reuse the production bean name; `traceId` is added in `createResponseEntity`, which runs after the problem body exists.
4. **Frontend skeleton** (`3a011e6`): the section 3 shell and client; two dependency-cruiser rules were scoped so `src/app` may wire the client and components may import API types.
5. **Plan, keys and planning artefacts** (`5b6f574`, `b4e66b8`, `272ee0d`): this document as Markdown and PDF, the mock signing key in `dev-mcc`, the dependency DAG, processor output and dev report with the repair notes.
6. **Tracker** (`eb148d1`): the 59-ticket set of section 7, generated from the build order with the orchestrator's effort per ticket and vetted before publishing.

Hand-over to `/do-work` starts at ticket 03.

---

## 9. Verification

| Step | Command / check | Expect |
|---|---|---|
| Toolchain | `java -version`, `node -v`, `docker version` | Temurin 21, Node 24, Docker 29 |
| Keys | `bash scripts/gen-dev-keys.sh`; `keytool -list -keystore backend/src/main/resources/dev-keystore.p12 -storepass changeit` | one EC `mcc-dev` entry; base64 keys in `application-dev-mcc.yml` |
| Compose (without H0) | `docker compose up -d postgres localstack`; `awslocal s3 ls`; `awslocal kms list-aliases` | postgres healthy; two buckets; `alias/annualva-dev` |
| Compose (with H0) | `docker compose up -d`; `curl -sk https://localhost:9080/realms/SSO/.well-known/openid-configuration`; `curl localhost:908{1,2,3}/actuator/health` | issuer returned; three UP |
| Backend gate | `cd backend && ./mvnw -B -ntp clean verify` | Seam 1 green on Testcontainers; JaCoCo line+branch ≥ 0.80; ArchUnit green |
| Backend run | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev-mcc`; `curl -sk https://localhost:8443/actuator/health`; `…/.well-known/jwks.json` | UP; one EC P-521 JWK |
| Frontend gate | `cd frontend && npm ci && npm run check` | Prettier, tsc, vite build, fallow, depcruise, no-raw-html all pass |
| Sign-in (with H0) | browser `https://localhost:5173` → Keycloak → MockPass persona `smo.a` | SMO inbox under the banner; `GET /api/session` shows `uuid`, mapped email, `roles:["SMO"]` |
| Step-up / session | trigger a step-up action as `poc.1`; sign out; idle past 30 min | interstitial → fresh login → success + audit; 401 flips UI to signed-out |
| Mail and scan (with H0) | upload `report_quarantined.xml`; `docker compose logs -f mock-mcns \| grep Event=email` | plain rejection message; email line with System/state/due/link and no Finding detail |
| Tracker | `ls .scratch/annual-va/issues`; `/do-work` picks 01 | tickets in §7 order; `reports/dev-report.html` present |
| Review gate | `/code-reviewer` on the scaffold branch | `artifacts/code-reviewer/01-*-compliance.html` PASS (im8 deferrals named) |

Results on 2026-10-05: the toolchain, keys, compose (without ticket 01), backend gate and frontend gate rows pass. The backend run row reached the database connection (Docker Desktop was not running at the last check), which also shows that the missing `mock-m-sso` certificate path does not block startup. The sign-in, step-up, mail and scan rows wait for ticket 01. The tracker row passes (59 tickets; `/do-work` will pick 03). The review-gate row runs with the first `/do-work` ticket.
