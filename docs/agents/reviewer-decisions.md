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

## VA Cycle calendar is computed, the va_cycle table arrives with ticket 41

- **Area:** VA Cycle and Quarter calendar (ticket 06, ADR 0015), data model in `docs/implementation-plan.md`
- **Finding:** ticket 06 computes the Quarters, windows and due dates from the year, the `Clock` bean and two properties and adds no `va_cycle` table, whereas the implementation plan's data model lists one (Quarter starts, `closes_on`, `closed_at/by`, `first_live_quarter`). Ticket 41 needs a table for the close record and an early-close override of `closes_on`.
- **Chosen action:** accept the computed calendar and keep ADR 0015. The `va_cycle` table is added by ticket 41 (VA Cycle lifecycle) with the close record; the plan and ticket 41 say so. Decided 2026-10-05.
- **When to reuse:** any finding that the calendar has no table or deviates from the plan's data model; any ticket that needs a stored Cycle record before 41 should raise it rather than add the table ad hoc.

## First live Quarter is configuration until someone needs to change it

- **Area:** VA Cycle calendar (ticket 06, ADR 0015), the one-off first live Quarter
- **Finding:** the spec calls it "a one-off first live Quarter setting" and the plan puts it in `va_cycle.first_live_quarter`, but ticket 06 reads it from `annualva.calendar.first-live-cycle` (2027) and `first-live-quarter` (3), validated at startup, so changing it needs a deployment and a VA POC cannot change it in the app.
- **Chosen action:** keep configuration for now. Add a stored setting only if the cyber team asks to change it without a deployment; the plan's `va_cycle` row says it is configuration until then. Decided 2026-10-05.
- **When to reuse:** any finding that the first live Quarter is not stored or not editable in the app; revisit only on a request to change it without a deployment.

## System Register data model: uuid id plus code, Unlinked SMO computed

- **Area:** System Register (ticket 08, ADR 0017), the `va_system` table and the Unlinked SMO flag
- **Finding:** spec line 159 says the Register "holds the Unlinked SMO flag" and the plan's `va_system` row had the business System ID as `id` and a stored `smo_unlinked` column. Ticket 08 builds `va_system` with a uuid `id` plus a unique citext `code` (the System ID) and computes Unlinked SMO from `app_user.sso_id` when a preview is built or read, so it clears the moment the person signs in. The table is created in ticket 08; only ticket 10 writes it.
- **Chosen action:** accept as built and keep ADR 0017. The flag is computed, not stored; ticket 10's Register table recomputes it when it reads. No stored `smo_unlinked` column and no refresh hook on sign-in. Decided 2026-10-06.
- **When to reuse:** any finding that the Register's flag is not a stored column or that `va_system` deviates from the plan's data model; revisit only if listing the Register by Unlinked SMO becomes too slow to compute.

## Register workbook malware scan is a tracked deferral to ticket 13

- **Area:** System Register upload (ticket 08, ADR 0017), IM8 as-12 (uploads are malware-scanned before they are read)
- **Finding:** ticket 08 parses the Register `.xlsx` with Apache POI with no malware scan, because no scanner boundary exists in the code yet. The im8 check reports as-12 as FAIL; ticket 13's text covered Scan Reports only, so the Register upload had no owner until ticket 08 added notes.
- **Chosen action:** accept the tracked deferral under "IM8 controls owned by a later ticket". Ticket 13 builds the scanner boundary and must also route the Register workbook through quarantine and scan (an acceptance checkbox on ticket 13); ticket 51 builds the production adapter. Mitigations until then: bytes-sniffed type, 2 MB and archive caps, no macros, formulas never evaluated, DOCTYPE refused, file never stored, VA POC only. Do not build a scanner in ticket 08. Decided 2026-10-06.
- **When to reuse:** any gate finding that the Register upload is not malware-scanned; the control must pass in ticket 13's own gate, including the choice between a synchronous scan and a pending state.

## Reading the Register list is not audited

- **Area:** System Register read (`GET /api/systems`, ticket 10, ADR 0018 item 7), spec story 84 (audit of every view)
- **Finding:** story 84 asks for an audit entry for "every view, download, upload ... Register change, role change and export", but the Register list names no report or Finding, only System names and SMO and Cyber Manager emails. The template download and the preview read are audited.
- **Chosen action:** keep `GET /api/systems` unaudited; the Register list is not report or Finding data and an entry per page load is low value. Decided 2026-10-06.
- **When to reuse:** any finding that a Register or System list read is not audited. Reads of reports, Findings and previews stay audited; revisit if the list begins to carry report content.

## Reading a single VA Submission is audited unless the owning SMO/Tech Lead reads it

- **Area:** VA Submission read (`GET /api/submissions/{id}`, ticket 12, ADR 0020), spec story 84 and IM8 lm-4 (audit of every view)
- **Finding:** story 84 asks for an audit entry for every view. The submission record carries only state, the `overdue` and `endorsedLate` flags, due dates and user ids, no report or Finding content. The SMO inbox (ADR 0019) and the Register list (above) are unaudited for that reason, but neither is a single-record view.
- **Chosen action:** option (c). Audit a read of a single VA Submission when the caller is not the owning SMO/Tech Lead of that System (a Cyber Manager or a VA POC); the owner's own reads are not audited. Entries carry ids and fixed codes only (ADR 0014). Decided 2026-10-13.
- **When to reuse:** any finding about auditing a single-record read of a VA Submission, or a later read added to the same record. Lists and the inbox stay unaudited. Reads that carry report, Finding or evidence content are audited for every role.

## Scan Report upload size limits and the file cap are accepted as built

- **Area:** Scan Report upload pipeline (ticket 13, ADR 0021 decision 7), implementation plan line 158 ("Formats and limits (ADR, human confirms)") and H3 ("approve size limits")
- **Finding:** the ticket says "per-type size limits (ADR)" and the plan leaves the numbers for a human to confirm. Ticket 13 built them from the plan: Dependency-Check XML/JSON 25 MB, XCCDF 25 MB, ARF 50 MB, Nessus 50 MB, Burp 50 MB, a 50 MB request ceiling, and at most 20 live files per Scan Report and kind. A file is held in memory and scanned before an over-limit one is rejected as `TOO_LARGE`.
- **Chosen action:** accept the limits and the 20-file cap as built; ADR 0021 stays the one place that lists them. Changing one later is a one-line constant change plus the ADR table. Decided 2026-10-13.
- **When to reuse:** any finding about the per-type limits, the 50 MB ceiling or the 20-file cap; revisit only if H3 sign-off or real reports change a number.

## File status and list reads follow the submission-read rule; downloads are always audited

- **Area:** Scan Report file reads (ticket 13), spec story 84 and IM8 lm-4 (audit of every view); extends "Reading a single VA Submission is audited unless the owning SMO/Tech Lead reads it" (2026-10-13)
- **Finding:** the ticket says every view and download is audited. A file's status and the file list carry a name and a status but no report content, and the owning SMO/Tech Lead's screen polls them every 3 seconds while a file is scanned.
- **Chosen action:** audit a status or list read as `FILE_VIEWED` when the caller is not the owning SMO/Tech Lead (a Cyber Manager or a VA POC); the owner's reads are not audited. A download is audited for every role, the owner included. Decided 2026-10-13.
- **When to reuse:** any finding that a file status or list read is not audited for the owner. Reads that carry report, Finding or evidence content are audited for every role; revisit if the status begins to carry content.

## A Sanitized scan verdict is a rejection, not an accepted copy

- **Area:** malware scan verdicts (ticket 13, ADR 0021 decision 5, `scan` module shared by the Scan Report pipeline and the Register upload; the ticket 51 production adapter), IM8 as-12
- **Finding:** the implementation plan (line 258) and ADR 0021 accepted `CLEAN_SANITIZED` and kept the scanner's altered copy. The IM8 review skill requires a Sanitized verdict to end in a mismatch state with no download and a plain "modified during security scanning, re-upload the original" message. The gate flagged the accepted copy as High.
- **Chosen action:** follow the standard. `CLEAN_SANITIZED` is rejected with its own reject code and a plain message asking for the original to be re-uploaded; no bytes are kept. It applies to Scan Report uploads and to the Register workbook, and ticket 51's adapter must map the scanner's Sanitized to it. This supersedes ADR 0021 decision 5 and the plan note on line 258. Decided 2026-10-13.
- **When to reuse:** any finding about Sanitized, modified or altered scan results, for any file kind. Revisit only if the organisation's standard changes.

## Report parser caps: 20,000 Findings and 50,000 occurrences per file

- **Area:** report parsers (ticket 14, ADR 0022 decision 9; ticket 15, ADR 0023), the per-file caps on Findings and occurrences
- **Finding:** a file parsed into Findings is capped at 20,000 Findings and 50,000 occurrences (and, for Dependency-Check, 20,000 vulnerabilities and 1,000 packages per dependency, plus a 4,000-character cap on any string value read) to bound memory on a crafted file from an authenticated SMO. A file over a cap is rejected as `REJECTED_TOO_MANY_FINDINGS` with its own plain message. A Secure Configuration scan reaches the occurrence cap at about 250 hosts each failing 200 rules, but the accepted 50 MB ARF / 25 MB XCCDF byte limits usually bind first. These counts were first recorded as a proposal.
- **Chosen action:** accept the caps as built, for every report format. A person who hits one exports in parts (up to 20 files per kind). Decided 2026-10-14.
- **When to reuse:** any finding about the per-file Finding or occurrence caps or their message. Revisit only if real exports (ticket 58) or the H3 sign-off come near a cap; a per-format cap is the first option then.

## A 404 on a content-bearing read is not audited

- **Area:** audit of reads (spec story 84, IM8 lm-4), the Findings list and the file metadata read (tickets 14, 15), ADRs 0013 and 0014
- **Finding:** a 404 is deliberately identical for a missing file and one the caller may not see, and no 404 in the app is audited. The question was whether a 404 on a read that carries report content should be audited.
- **Chosen action:** keep every 404 unaudited, including on content-bearing reads. A refused read is still recorded where a 401 or 403 is written (ADR 0014), and a successful content-bearing read is audited for every role. Decided 2026-10-14.
- **When to reuse:** any finding that a 404 on a report, Finding, file or evidence read is not audited.
