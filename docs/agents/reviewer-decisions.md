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

## A DOCTYPE is skipped and never obeyed; only Burp's internal subset is accepted, as Burp writes it

- **Area:** report XML parsers (tickets 14 to 17, ADR 0021 item 7, ADR 0025 item 2), implementation plan line 158 and ticket 17's acceptance wording
- **Finding:** the plan said "DOCTYPE forbidden except Burp's internal subset" and ticket 17 said every other format forbids DOCTYPE. Tickets 14 to 16 skip a DOCTYPE that declares nothing and their tests assert it. `SecureXml` turns DTD support and external entities off for every format, so nothing is loaded or expanded either way, and an entity reference is an error.
- **Chosen action:** accept as built. A DOCTYPE is skipped and never obeyed for the Dependency-Check, SCAP and Nessus parsers; the Burp parser accepts only Burp's own internal subset (ELEMENT and ATTLIST declarations, no external id) checked on the first 32 KB of bytes. The plan line and ticket 17's wording were amended to say so. Decided 2026-10-15.
- **When to reuse:** any finding that a non-Burp report parser does not refuse a DOCTYPE. Revisit only if real exports (ticket 58) or a security review show a reason to refuse every DOCTYPE; that is a one-line check per parser.

## An encrypted PDF is refused as supporting evidence by a byte-level check

- **Area:** supporting evidence (ticket 18, ADR 0026), im8 as-12
- **Finding:** evidence is accepted as `READY` on a `%PDF-` prefix after the malware scan. The scanner cannot inspect a password-protected PDF, so an encrypted file would be served to Cyber Managers and VA POCs uninspected. The back end has no PDF library, and ADR 0026 says evidence is compared by bytes and never read.
- **Chosen action:** refuse a PDF that carries `/Encrypt` in its trailer, with a bounded byte-level check on the tail of the file. No PDF library is added and nothing parses the file. The file is rejected with a plain "re-upload without a password" message. Decided 2026-10-15.
- **When to reuse:** any finding that evidence the scanner cannot read (encrypted or password-protected) is accepted. A file that hides `/Encrypt` in an object stream, or whose cross-reference stream data is longer than the 64 KB window (a large PDF 1.5 file), is not caught until ticket 51's production scanner; do not add a parser to close that gap.

## An approval memo is an ordinary evidence file, counted against its Scan Report's 20-file cap

- **Area:** Finding Status proposals (ticket 19, ADR 0026 and ADR 0027)
- **Finding:** ADR 0026 left it to ticket 19 whether the Risk Accepted approval memo counts against the evidence cap or has a kind of its own. As built, the memo is an EVIDENCE file uploaded under any Scan Report of the VA Submission, must be `READY`, counts against that report's 20-file cap, and only a Risk Accepted proposal may link one.
- **Chosen action:** accept as built. No memo kind, no separate cap. Changing it later is the cap constant in ADR 0021 or a new kind with a migration. Decided 2026-10-17.
- **When to reuse:** any finding that a memo shares the cap with scan evidence or sits under an arbitrary Scan Report.

## A Remediation Planned target date is after today in Singapore and at most 10 years ahead

- **Area:** Finding Status proposals (ticket 19, ADR 0027), `ProposalFields.MAX_YEARS_AHEAD`
- **Finding:** the plan says only "future target_date". The 10-year ceiling is a chosen format bound that keeps the date real (as-1) and refuses values like 9999-12-31; a date beyond it is a 400 `invalid-field`.
- **Chosen action:** accept 10 years as the constant. Decided 2026-10-17.
- **When to reuse:** any finding that the target date has an upper bound or that the bound is not in the spec. Revisit only if a real remediation plan needs longer.

## The proposal history is append-only for every connection; ticket 49 replaces the trigger

- **Area:** `finding_status_proposal` (V14, ticket 19, ADR 0027), retention (ticket 49)
- **Finding:** V14 forbids UPDATE, DELETE and TRUNCATE on the table for every connection, with no retention bypass, as V5 does for `audit_entry`. Retention (Cycle plus 3 years) must hard-delete, so the trigger needs replacing, and the foreign keys (`finding.current_proposal_id`, the proposal's `finding_id` and `approval_memo_file_id`) force an order: clear the Finding's pointer, delete the proposals, then delete the Findings and files.
- **Chosen action:** accept as built and add an acceptance line to ticket 49 naming the trigger replacement and the deletion order. Decided 2026-10-17.
- **When to reuse:** any finding that the proposal table has no retention bypass or that application code cannot delete from it.

## A Bulk-set batch holds at most 1000 Findings

- **Area:** Bulk-set (ticket 20, ADR 0028), `BulkProposals.MAX_FINDINGS` and the screen's `MAX_BATCH_SIZE`
- **Finding:** the spec gives no limit. The cap bounds the row lock set and the request (about 40 KB of ids); over it is a 400 `invalid-field` naming `findingIds` and Apply is disabled on the screen.
- **Chosen action:** accept 1000 as built. Decided 2026-10-17.
- **When to reuse:** any finding that the batch size is bounded or that the bound is not in the spec. Revisit only if a real System routinely needs more than 1000 Findings in one filter.

## Readiness counts name awaiting review and unproposed explicitly

- **Area:** `GET /api/submissions/{id}/readiness` (ticket 20, ADR 0028); read by tickets 23, 25 and 29
- **Finding:** `pending` meant review state PENDING (proposed, awaiting the Cyber Manager), and the screen computed "still to propose" as `total - proposed`. The word can be read either way.
- **Chosen action:** rename `pending` to `awaitingReview` and add an explicit `unproposed` count; no field is called `pending`. Decided 2026-10-17.
- **When to reuse:** any later count of Findings by proposal or review state: use `awaitingReview` and `unproposed`, never a bare `pending`.

## A batch writes one audit entry per Finding as well as the batch entry

- **Area:** Bulk-set audit (ticket 20, ADR 0028, ADR 0014), IM8 lm-4, spec story 84
- **Finding:** a batch wrote one `BATCH_PROPOSED` entry with no Finding ids, so an audit search by Finding missed changes made in a batch, and tracing them relied on the proposal table that ticket 49's retention deletes.
- **Chosen action:** keep the `BATCH_PROPOSED` entry and also write one `STATUS_PROPOSED` entry per Finding, carrying the `batch_id` (ids and fixed codes only, no text). Decided 2026-10-17.
- **When to reuse:** any bulk change to many records: audit each record as well as the batch.

## Rescan evidence names both the report's scan date and the upload date

- **Area:** rescan Remediated proposals (ticket 21, ADR 0029), spec story 39
- **Finding:** the evidence named only the rescan's business upload date; the report's own scan date was not used, though a reviewer usually means it.
- **Chosen action:** name both: the scan date read from the report when it has one, and the upload date (Asia/Singapore business date of `uploaded_at`). Decided 2026-10-18.
- **When to reuse:** any generated evidence or summary that dates a file.

## A rescan proposal keeps the uploader as proposer and also names the uploader on its audit entry

- **Area:** rescan proposals (ticket 21, ADR 0029), `finding_status_proposal.proposed_by`, `STATUS_PROPOSED`
- **Finding:** `proposed_by` is the SMO who uploaded the rescan, `proposed_as=RESCAN` marks it automatic, and its audit entry had no actor.
- **Chosen action:** keep `proposed_by` as the uploader and also record the uploader as the actor on the rescan's `STATUS_PROPOSED` and `FILE_REPLACED` entries, with `proposed_as` telling a reader it was automatic. Decided 2026-10-18.
- **When to reuse:** any change the system makes as a consequence of a person's action: attribute it to that person and mark it automatic.

## A superseded file's occurrences are deleted, and the rescan proposal points at the old file

- **Area:** rescan settlement (ticket 21, ADR 0029)
- **Finding:** the old file's occurrences are deleted, so a Finding a rescan proposes Remediated shows no places; where it was reported was only recoverable by downloading the old file.
- **Chosen action:** keep deleting, and show the Cyber Manager the replaced file's name and a download link with the rescan proposal. Decided 2026-10-18.
- **When to reuse:** any finding that rescan history is lost or that occurrence reads should filter superseded files.

## Supporting evidence cannot be replaced; it is attached again

- **Area:** file replacement (ticket 21, ADR 0029), evidence (ADR 0026)
- **Finding:** only PARSED machine-readable files can be replaced; evidence rests in READY and gets `file-not-replaceable`, and still counts against the 20-file cap.
- **Chosen action:** accept as built. Decided 2026-10-18.
- **When to reuse:** any finding that evidence or a memo cannot be swapped; revisit with a delete-file ticket.

## A Finding reported again after a rescan proposed it Remediated loses that proposal as current

- **Area:** rescan settlement and ordinary settlement (ticket 21, ADR 0029)
- **Finding:** a later file reporting the key again only added places, so the Remediated/RESCAN proposal stayed current with false evidence, the Finding was not under Needs attention, and its severity was not recalculated.
- **Chosen action:** when a key whose current proposal is Remediated/RESCAN is reported again, clear `current_proposal_id` (history kept, only the pointer moves) and recalculate severity from its places. Decided 2026-10-18.
- **When to reuse:** any automatic proposal whose basis a later fact contradicts.

## Settlement locks every Finding it touches once, in id order, before writing

- **Area:** rescan and ordinary settlement (tickets 13 to 21, ADR 0029), proposals and Bulk-set (ADR 0028)
- **Finding:** the settlement locked the old file's Findings in id order, then the UPSERT locked other existing Findings in `unnest` order, so it could deadlock with a proposal or Bulk-set and either side could be the victim (a user would get a 500).
- **Chosen action:** before the UPSERT, take one id-ordered `SELECT ... FOR UPDATE` over every existing Finding the settlement will touch (the old file's and those matching the rescan's identity keys), used by both the rescan and the ordinary path; correct ADR 0029. Decided 2026-10-18.
- **When to reuse:** any transaction that locks more than one Finding: lock them all once, in id order, first.

## A rescan does not re-propose a Finding whose current proposal is already Remediated

- **Area:** rescan settlement (ticket 21, ADR 0029 item 5), spec story 39
- **Finding:** a rescan no longer reporting a Finding always wrote a Remediated/RESCAN proposal and reset the review, even when the current proposal was already Remediated (adding re-review for no new information).
- **Chosen action:** skip the rescan proposal when the current proposal's status is Remediated; still propose for any other status, including a Confirmed False Positive or Risk Accepted. Decided 2026-10-18.
- **When to reuse:** any automatic proposal that would repeat the current status.

## An early disposal of a superseded file deletes its bytes and keeps its record

- **Area:** retention of superseded files (ticket 21, ADR 0029 item 7; ticket 49), spec "superseded uploads after endorsement"
- **Finding:** the spec disposes of superseded uploads after endorsement, earlier than Cycle plus 3 years, but a RESCAN proposal (`finding_status_proposal.replaced_file_id`, append-only) and the rescan (`report_file.replaces_id`) reference the superseded `report_file` row, so deleting the row early fails on those foreign keys.
- **Chosen action:** the early disposal deletes only the superseded file's bytes from the file store and keeps its `report_file` row (name, hash, links) until the Cycle-plus-3-years disposal; a download of a disposed file answers with a plain "disposed" problem. Keep the foreign keys. Ticket 49 gains an acceptance line; ADR 0029 item 7 is amended. Decided 2026-10-18.
- **When to reuse:** any early disposal of a record other rows still reference: delete the content, keep the row until the full disposal.

## A VA Submission cannot leave Draft or Returned for Rework while any of its files is scanning

- **Area:** settlement of uploads and rescans (ticket 21, ADR 0021, ADR 0029); submit (ticket 25), exemption request (ticket 40), the Register's withdraw (ticket 43)
- **Finding:** a rescan is accepted only in Draft or Returned for Rework but can settle after the submission has left those states, and the settlement then wrote Remediated proposals into a frozen or read-only submission. Ordinary uploads already settle regardless of state, and the plan's "none still scanning" rule covered only submit.
- **Chosen action:** keep the settlement as built (one rule for ordinary and rescan uploads), and refuse every route that moves a VA Submission out of Draft or Returned for Rework while any of its files is still scanning: submit (ticket 25), exemption request (ticket 40) and the Register's withdraw (ticket 43, the row is Blocked). Those tickets gained acceptance lines; no code in ticket 21. Decided 2026-10-18.
- **When to reuse:** any new route that moves a VA Submission out of an editable state, or any finding that a settlement can land in a frozen submission.

## A Scan Report's mode is fixed once it holds anything; deletion is a later ticket

- **Area:** Scan Report modes (ticket 22, ADR 0030)
- **Finding:** a change to another mode is refused with 409 `report-not-empty` while the Scan Report holds a non-rejected report file or any Finding, and nothing can delete a report file or a manually entered Finding, so a mistake fixes the mode for that VA Submission.
- **Chosen action:** keep the refusal as built and raise ticket 60 to delete a report file or a manually entered Finding while the VA Submission is in Draft or Returned for Rework, which unlocks the mode. Decided 2026-10-18.
- **When to reuse:** any finding that a Scan Report's mode or content cannot be undone; point at ticket 60.

## A not-applicable justification is overwritten; ticket 28 decides what is kept beside a review

- **Area:** not-applicable justifications (ticket 22, ADR 0030 item 3), review (ticket 28)
- **Finding:** the justification is updated in place and a changed text resets the review to PENDING, so a Confirmed review never refers to unseen text, but after a Dispute and a rewrite the disputed wording is gone; proposals, by contrast, are append-only.
- **Chosen action:** keep as built; ticket 28 gained an acceptance line to decide with the human what text is stored beside a review. Decided 2026-10-18.
- **When to reuse:** any finding that a reviewed justification keeps no history.

## Manually entered Findings: limits 200/200/500 and an exact duplicate rule

- **Area:** manual Findings (ticket 22, ADR 0030 items 7 and 8)
- **Finding:** title at most 200, identifier 200, package or rule 500 characters (chosen bounds for IM8 as-1); a second Finding with the same report type, identifier and package or rule is refused (409 `duplicate-finding`), compared exactly and case-sensitively after cleaning, like the parsed keys.
- **Chosen action:** accept as built. Decided 2026-10-18.
- **When to reuse:** any finding about manual Finding field limits or case-insensitive duplicates.

## A changed scan date is flagged, not blocked, and the confirmed date is still judged

- **Area:** scan date confirmation (ticket 23, ADR 0031 items 2 and 5), audit `FILE_METADATA_CONFIRMED`
- **Finding:** ADR 0031 item 2 left a changed scan date unflagged, so confirming a later date could take a file out of the 90-day `tooOld` list unnoticed; spec line 229 says anything else the SMO changes is "flagged, not blocked" (code-review Spec P1, im8 M-1).
- **Chosen action:** flag it. The metadata view returns `scanDateEdited` when the confirmed date differs from the file's own day (for a Burp export, its export day; one rule for every format), and the Scan Report card marks it next to "Target edited". The age rule still judges the confirmed date, so a wrong file date can be corrected. `FILE_METADATA_CONFIRMED` gains the boolean details `date_changed` and `target_changed`, never the values. The readiness list is unchanged; the review screens (ticket 28 onwards) read the same flag. ADR 0031 item 2 is amended. Decided 2026-10-21.
- **When to reuse:** any SMO override of a value read from a report file: flag it beside the value and in the audit entry as a boolean, and do not block it.

## Overdue clears when a submit moves into a window that has not passed

- **Area:** the Overdue flag (ticket 26, ADR 0033 item 4), submit (ticket 25)
- **Finding:** the glossary says a VA Submission is Overdue when its current window has passed, but the ticket did not say whether a flag set in Draft survives a submit made while the Review Window is still open.
- **Chosen action:** keep as built. A submit keeps the flag and its `overdue_since` only if `review_due` has also passed; otherwise it clears both with no audit entry of its own, the `SUBMISSION_SUBMITTED` entry being the record and the earlier `SUBMISSION_OVERDUE` entry keeping the late submit. The sweep flags the VA Submission again if the review runs late. Decided 2026-10-23.
- **When to reuse:** any transition that moves a flagged VA Submission into a new window: the flag follows the current window, it is not a permanent "was late" mark.

## Not Started Systems are swept in every Cycle the app runs that is not closed

- **Area:** the Overdue sweep (ticket 26, ADR 0033 item 3, ADR 0019), added Systems (Release 2 story 10), reminders (ticket 38)
- **Finding:** a System added to the Register after its Quarter's Submission Window has passed is flagged Not Started Overdue on the next sweep; `va_system.added_on` could be used to skip it.
- **Chosen action:** keep as built until Release 2 gives an added System its own windows; do not use `added_on`, which would also hide a real non-start by a System added late in its Quarter. Ticket 38 and the Release 2 added-System work note that an added System's SMO is reminded at once. Decided 2026-10-23.
- **When to reuse:** any finding that an added System is Overdue or reminded too early before Release 2.

## The Overdue sweep skips VA Submissions of retired Systems

- **Area:** the Overdue sweep (ticket 26), retiring a System (ticket 43)
- **Finding:** the sweep flagged and audited the Draft or In Review VA Submission of a retired System, which nobody can see or act on until ticket 43 withdraws it.
- **Chosen action:** exclude VA Submissions whose System is retired from the sweep now, with a Seam 1 test. Decided 2026-10-23.
- **When to reuse:** any job that acts on VA Submissions: skip those of retired Systems unless the job is the one that withdraws them.

## The Cyber Manager inbox lists Not Started Systems too

- **Area:** Cyber Manager inbox (ticket 27, ADR 0034, story 48)
- **Finding:** story 48 and the ticket say the inbox shows the caller's "VA Submissions"; a Not Started System has none, so the wording would leave it off.
- **Chosen action:** keep as built. Every System in the Cyber Manager's review scope is listed, Not Started included, under "Waiting on others" (never above "Needs your review"), Overdue first. Decided 2026-10-24.
- **When to reuse:** any list of a role's Systems that could leave out Systems with no VA Submission: include them, grouped so they never crowd out work that needs action.

## The SMO inbox puts the later states under Waiting on others or Done until ticket 41

- **Area:** SMO inbox (ticket 27, ADR 0034 item 6, ADR 0019), Not Endorsed (ticket 41)
- **Finding:** Awaiting Endorsement and Exemption Requested show under "Waiting on others", and Endorsed, Exempted, Withdrawn and Not Endorsed under "Done" with "Nothing more to do", although Not Endorsed is a failed outcome.
- **Chosen action:** keep as built; ticket 41, which brings Not Endorsed into use, decides its own heading or label. Decided 2026-10-24.
- **When to reuse:** any finding about how the SMO inbox groups or labels a final state before its ticket lands.

## The Declaration text is published in the app by a VA POC

- **Area:** the Declaration text (ticket 24, ticket 61, ADR 0032, spec open question "the exact wording of the Declaration text, and who owns its versions")
- **Finding:** ticket 24 planned to ship the agreed wording as a new database migration through a change request; the human asked whether it could be set later in the app instead.
- **Chosen action:** a VA POC publishes each new Declaration version in the app (ticket 61): append-only versions, step-up, audited by version number only. The VA POC role owns the text. Ticket 61 decides whether a second approver is needed and whether a version can take effect from a chosen date. The agreed wording is still agreed with the PO and the cyber team (ticket 24) and published before UAT. Decided 2026-10-24.
- **When to reuse:** any other fixed text every user must accept: manage it as published, append-only versions in the app, not by migration.

## A review of a not-applicable justification keeps a copy of the wording it reviewed

- **Area:** reviews of not-applicable justifications (ticket 28, V21 `report_review`, ADR 0030 item 3); follows "A not-applicable justification is overwritten; ticket 28 decides what is kept beside a review" (2026-10-18)
- **Finding:** the justification is overwritten in place, so after a Dispute and a rewrite the disputed wording would be lost.
- **Chosen action:** each `report_review` row copies the justification as it was reviewed, in the statement that writes the review; after a rewrite, the latest review still shows the wording it reviewed and why. The justification itself stays overwritten in place (no append-only history). A review names the version of the justification its screen showed and is refused if it has changed since. Decided 2026-10-24.
- **When to reuse:** any review of free text that can be rewritten after it is reviewed: copy the reviewed text into the review, and refuse a review of a version the reviewer did not see.

## Ticket 29: a batch review leaves out members already reviewed and is audited per Finding

- **Area:** batch review (ticket 29, ADR 0036 items 3 and 4)
- **Finding:** whether a batch review re-decides members already reviewed one by one, and whether it is audited once or per Finding.
- **Chosen action:** keep as built. Members reviewed one by one, or proposed again, are left out and reported (`REVIEWED`, `PROPOSAL_CHANGED`); a batch review writes one `BATCH_REVIEWED` entry and a `FINDING_REVIEWED` entry per Finding it decides. Decided 2026-10-25 by the user (all recommendations accepted).
- **When to reuse:** any group action whose members can also be acted on singly: never overwrite a single decision, and audit each member as well as the group.

## Ticket 30: the return comment is optional, and a VA POC counts at the endorsing step only by acting

- **Area:** return for rework and pass to endorsement (ticket 30, ADR 0039 items 3 and 5), segregation of duties
- **Finding:** whether the Cyber Manager's return comment is required, and how a VA POC is placed at the endorsing step.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - The Cyber Manager's return comment is optional, since each Dispute carries its own required comment; a VA POC's return requires one (ticket 35).
  - Holding the VA POC role assigns nobody to the endorsing step: a VA POC counts there once they act (endorse, return, decide an Exemption), and the Register naming them the System's SMO/Tech Lead or Cyber Manager refuses them the endorsing step of that System.
- **When to reuse:** any segregation-of-duties question about a VA POC; any summary text beside required per-item comments.

## Ticket 31: the rework queue's "new" means no proposal yet, and only the SMO/Tech Lead reads it

- **Area:** guided rework queue (ticket 31, ADR 0041 items 2, 3 and 6)
- **Finding:** three readings left open by the ticket.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - "New Critical/High pending" is a Finding with no proposal yet, the same as "needs attention".
  - Only the System's SMO/Tech Lead reads the rework queue; others read the Findings list.
  - Disputed not-applicable justifications are shown on the rework screen with a link to the prepare screen to rewrite them, not as queue items.
- **When to reuse:** any work list for the SMO/Tech Lead after a return.

## Ticket 32: a Finding never reviewed is not "changed since last review"

- **Area:** re-review after a return (ticket 32, ADR 0040 item 2)
- **Finding:** whether Findings never reviewed (new by hand or by a rescan, or left PENDING at the return) count as changed.
- **Chosen action:** keep as built: changed means the latest review is not of the current proposal; never-reviewed Findings show as awaiting review and still block the pass. Decided 2026-10-25 by the user (all recommendations accepted).
- **When to reuse:** any filter of what a Cyber Manager must look at again.

## Ticket 33: the Cycle matrix and the VA POC board

- **Area:** VA POC board and Cycle matrix (ticket 33, ADR 0037 items 3 to 6)
- **Finding:** five readings left open by the ticket.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - "Reviewed" on the matrix is the Cyber Manager's pass to endorsement.
  - A VA POC return keeps the last reviewed date (built by ticket 35, ADR 0047 item 4).
  - A Withdrawn VA Submission keeps its row, read-only and out of the counts.
  - A person who is both VA POC and Cyber Manager gets the VA POC board, their own Systems included.
  - The VA POC board's segregation-of-duties marker stayed false until ticket 34 added the endorsing step (since built, ADR 0042 item 5).
- **When to reuse:** any VA POC dashboard or milestone question.

## Ticket 34: deciding an Exemption is the endorsing step, and the step-up comes before the lookup

- **Area:** endorse (ticket 34, ADR 0042 items 1 and 2), Exemptions (ticket 40)
- **Finding:** whether deciding an Exemption is a step of its own for segregation of duties (the glossary lists one), and whether the step-up or the lookup comes first.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - A VA POC counts at the endorsing step only by acting there, or when the Register names them the System's SMO/Tech Lead or Cyber Manager (as ticket 30).
  - Deciding an Exemption is the VA POC's endorsing step (`ENDORSE`), so a VA POC who rejected an Exemption may still endorse; `DECIDE` stays allowed and unused.
  - The step-up is checked before the lookup, so a stale session learns nothing.
- **When to reuse:** any VA POC action and its segregation-of-duties step; the order of step-up and lookup on any step-up command.

## Ticket 35: one return endpoint, a comment-only VA POC return, and one clean migration sequence before release

- **Area:** VA POC return (ticket 35, ADR 0047 items 1, 2, 4 and 6), Flyway versions on the integration branch
- **Finding:** four readings left open by the ticket, and tickets merging out of version order (V135 and V141_35 land after V144), so a database migrated past them needs `outOfOrder` or a reset.
- **Chosen action:** keep as built, and renumber the migrations. Decided 2026-10-25 by the user (all recommendations accepted, Flyway as below).
  - One `/return` endpoint; the caller's role and the state pick the step (the Cyber Manager's return unless Awaiting Endorsement or the caller is not this System's Cyber Manager).
  - A VA POC return carries a required comment only, no challenged Findings.
  - The Cycle matrix keeps the last reviewed date after a VA POC return.
  - A VA POC return mails both the SMO/Tech Lead (`VA_POC_REWORK`) and the Cyber Manager (`VA_POC_RETURNED`) with the `RETURNED_FOR_REWORK` template.
  - Flyway: once every ticket is merged and before release, every migration on the integration branch is renumbered into one clean sequence, so no environment runs with `outOfOrder`. The orchestrator does the renumbering.
- **When to reuse:** a command shared by two roles; any migration-ordering finding during the run (do not renumber per ticket; the release renumbering covers it).

## Ticket 36: Disputed travels with the return, and the in-app list is open to the four roles

- **Area:** in-app notifications (ticket 36, ADR 0043 items 6 and 7)
- **Finding:** how Disputed reaches the SMO/Tech Lead, and whether a former SMO/Tech Lead with no role can read the list.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - Disputed reaches the SMO/Tech Lead through the return's `REWORK` notice; nothing is sent per Dispute.
  - The in-app list is open to the four roles only (deny by default); a former SMO/Tech Lead with no role gets the transfer by mail (ticket 37).
- **When to reuse:** any new notice that a later move already announces; any endpoint someone without a role might want.

## Ticket 37: only the spec's required messages are mailed

- **Area:** mail outbox (ticket 37, ADR 0046 item 2 and consequences)
- **Finding:** which notices are mailed, whether Disputed has its own mail, and what SIT starts with before ticket 57.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - Mailed: Return for Rework (with Disputed), Overdue, Register changes. Submit, pass to endorsement and the Cycle close's Not Endorsed stay in-app.
  - No separate Disputed mail; it travels with the Return for Rework mail.
  - SIT starts with placeholder template ids and `https://localhost:8443` until ticket 57 records the real ones.
- **When to reuse:** any new `NoticeAction`: mail it only if the spec lists it as required.

## Ticket 38: reminder copies go on the weekly Overdue reminder, with four shared templates

- **Area:** deadline reminders (ticket 38, ADR 0052 items 3, 4 and 5)
- **Finding:** three readings left open by stories 88 and 89 and ticket 57's template list.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - The copy (Cyber Manager on the SMO/Tech Lead's, VA POCs on the Cyber Manager's) goes on the weekly Overdue reminders only.
  - A VA POC's copy is one item each that nothing clears, not a pair.
  - Four shared reminder templates: due soon, due today, overdue weekly, copy.
- **When to reuse:** any reminder or copy question; ticket 57 onboards four reminder templates, not separate copy variants.

## Ticket 39: SMO Unreachable is a flag for VA POCs, with no notification

- **Area:** bounce handling (ticket 39, ADR 0053 items 3, 5, 6 and 7)
- **Finding:** whether anyone is notified, who sees the flag, which date it keeps, and the PO's acknowledgement that MCNS reports no later bounce.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - No notification; the flag shows on the Register table and the VA POC's Quarter board.
  - Only VA POCs see it, and not on the Cycle matrix.
  - It keeps the date of the first refusal until it clears.
  - Acknowledged: a mail MCNS accepts that bounces later is never flagged; the weekly digest is Release 2.
- **When to reuse:** any flag about a System that clears without a person acting.

## Ticket 40: Exemptions

- **Area:** Exemption request and decision (ticket 40, ADR 0048 items 2 to 6)
- **Finding:** six readings left open by the ticket.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - A rejected request that created the VA Submission leaves the row in `NOT_STARTED`, which a later start takes over.
  - The request names a supporting evidence file already uploaded and fully checked; a Not Started System gives text evidence unless the SMO/Tech Lead starts first.
  - Deciding an Exemption is the endorsing step for segregation of duties (as ticket 34).
  - A request made while Awaiting Endorsement leaves the endorsement notice open; the three Exemption notices are in-app only.
  - A prior VA falls from 3 months before the Quarter's first day to the day before (not after today); a scheduled VA from the day after the Quarter's last day to 3 months after. A VA during the Quarter does not count.
- **When to reuse:** any Exemption or conditional Exemption change (ticket 42 builds on these).

## Ticket 41: a VA POC closes a Cycle early only after Q4, and Not Endorsed sits under Done

- **Area:** VA Cycle lifecycle (ticket 41, ADR 0044 items 3, 4 and 7); settles "The SMO inbox puts the later states under Waiting on others or Done until ticket 41" (2026-10-24)
- **Finding:** when an early close is allowed, how the SMO inbox shows Not Endorsed, and what the older Cycle keeps during the overlap.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - A VA POC may close a Cycle early only after Q4 ends, during the 4-week grace.
  - The SMO inbox lists Not Endorsed under Done with no next action, a danger flag and "Not endorsed: the VA Cycle closed before it was endorsed".
  - During the overlap the older Cycle keeps only which Systems belong to it and their Quarters; who may see or act follows the current Register.
- **When to reuse:** any Cycle close or overlap question; any failed final state in an inbox.

## Ticket 42: conditional Exemption lapse

- **Area:** conditional Exemption (ticket 42, ADR 0051 items 2 to 7)
- **Finding:** six readings left open by the ticket.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - Windows after a lapse that would run past the Cycle's close are cut to end the day before the next one.
  - Record VA and confirm VA skip the closed-Cycle check for every Quarter, not only Q4.
  - The lapse notices (`EXEMPTION_LAPSED`, `EXEMPTION_LAPSED_NOT_ENDORSED`, `CONFIRM_VA`, `VA_CONFIRMED`) are in-app only.
  - Confirm VA reuses the step-up intent `exemption-decide`.
  - The "Exemption lapsed" flag never clears, like Endorsed Late.
  - The VA happened form requires evidence text as well as the date.
- **When to reuse:** any change to Exemption lapse, its windows or its notices.

## Ticket 43: Register change effects

- **Area:** Register change effects on in-flight work (ticket 43, ADR 0049)
- **Finding:** three readings left open by the ticket.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - The Quarter board's Withdrawn column stays empty (a Withdrawn VA Submission belongs to a retired System, which no dashboard lists).
  - A Not Started row left by a rejected Exemption counts as no work: retiring leaves it Not Started on a retired System; moving leaves its Quarter, windows and Overdue as they were.
  - Segregation of duties on a Register change counts both who acted and who was assigned before the change (matters only in Release 2, since Release 1 holds every transfer back).
- **When to reuse:** any Register change that touches in-flight work.

## Ticket 44: the export download needs no second step-up, and open Deviations count every such proposal

- **Area:** management summary export (ticket 44, ADR 0045 items 4 and 6)
- **Finding:** whether downloading needs its own step-up (IM8 ac-2), and what "open Deviation" counts.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - The step-up guards the request; the file is the requester's alone and lasts 24 hours, so the download needs no second step-up.
  - "Open Deviation" is every Finding whose current proposal is Risk Accepted or Remediation Planned, whatever its review state.
- **When to reuse:** any export file or management count of Deviations (ticket 45's PDF follows it).

## Ticket 45: the PDF uses PDFBox and prints `?` outside Windows-1252

- **Area:** management summary PDF (ticket 45, ADR 0054 items 2 and 5)
- **Finding:** the plan named OpenPDF (LGPL/MPL); Helvetica cannot draw characters outside Windows-1252.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - Apache PDFBox 3.0.8 (Apache 2.0), pinned; the tests read the PDF back.
  - Characters outside Windows-1252 print as `?` in the PDF for Release 1; the Excel keeps them. Embedding Noto Sans is the change if the Register holds such names.
- **When to reuse:** any PDF output or third-party library licence question.

## Ticket 46: role lapse returns

- **Area:** role lapse and automatic return (ticket 46, ADR 0055 items 3 to 6)
- **Finding:** four readings left open by the ticket.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - A Quarter's start counts as a return moment: a grant whose System's Quarter began within 90 days does not lapse, and one lapsed before its Quarter began comes back (ticket 47's review covers a never-used role).
  - Every Register apply returns everyone it names.
  - A submit also returns the System's Cyber Manager.
  - A VA POC returns only through `annualva.va-poc-role-returns`, emptied at the next change request.
- **When to reuse:** any role lapse or return trigger.

## Ticket 47: role-holder review and suspension

- **Area:** role-holder review, suspension and identity-provider disablement (ticket 47, ADR 0056 items 3 and 5 to 8)
- **Finding:** five readings left open by the ticket and IM8 ac-4.
- **Chosen action:** keep as built. Decided 2026-10-25 by the user (all recommendations accepted).
  - A Register upload naming the same person again does not lift a suspension; a VA POC restore, or an upload naming someone else, does.
  - The disabled signal is the claim `afcas.status` (absent means enabled; any value but `active` means disabled) until H4 onboarding confirms what MCC SSO sends.
  - The session check fails open, with a warning, when the identity provider cannot answer; until ticket 51 wires the adapter only the sign-in and step-up callback detect disablement.
  - A review is due after any Register apply as well as after 3 months; nothing is mailed when it falls due.
  - Only SMO/Tech Lead and Cyber Manager grants are suspendable; a VA POC may suspend the other VA POC's per-System grants; re-enabling at the provider restores no grant.
- **When to reuse:** any role suspension, review or identity-provider status question.

## Ticket 48: the Cyber Manager's audit history, and reading the log is audited

- **Area:** Cyber Manager audit history (ticket 48, ADR 0038 items 2, 3 and 5), reads of the audit log (IM8 lm-4)
- **Finding:** three readings of the ticket, and whether reading the audit log is itself audited (no earlier decision covered it).
- **Chosen action:** keep items 2, 3 and 5 as built, and audit reads of the log. Decided 2026-10-25 by the user (all recommendations accepted; the audit of reads as below).
  - "Acted at the reviewing step" is a committed review recorded at `REVIEW` (Finding, justification or batch).
  - Without `submission=`, a Cyber Manager gets the history of every VA Submission they reviewed.
  - The history holds every entry about the VA Submission, whoever wrote it, others' reads and refusals included.
  - Reading the audit log is audited, for a VA POC and a Cyber Manager alike: an `AUDIT_LOG_VIEWED` entry carrying the filter ids only. Ticket 50 builds it.
- **When to reuse:** any read of the audit log or of another person's audit entries.

## Ticket 49: retention dates, and per-row disposal checks as a follow-up

- **Area:** retention and disposal (ticket 49, ADR 0050 items 2, 3, 4 and 8, and the accepted delete window)
- **Finding:** four readings of "Cycle plus 3 years" and what goes with it, and a security finding: while a list is `APPROVED`, a transaction naming it can delete any row of the append-only tables, and only the audit log is checked row by row.
- **Chosen action:** keep items 2, 3, 4 and 8 as built, and check every history table row by row in ticket 62. Decided 2026-10-25 by the user (all recommendations accepted; the delete window as below).
  - "Cycle plus 3 years" is kept through the third anniversary of the Cycle's close date and due the next day; a Cycle never closed is never due.
  - Records belonging to no single VA Submission (audit log, Register previews, notifications) are disposed of up to the next Cycle's first day.
  - Retired Systems with nothing left outside the due Cycles are disposed of with their grants.
  - Only Endorsed VA Submissions dispose of superseded file content early; `FAILED` exports are not listed (a follow-up if they should go).
  - The delete window is accepted as provisional until ticket 62 makes every append-only history table's delete trigger check, row by row, that the row is on the `APPROVED` list.
- **When to reuse:** any retention date or disposal question; any new append-only table (its delete trigger needs ticket 62's per-row check).
