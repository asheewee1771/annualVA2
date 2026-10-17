# 19: List Findings and propose a Finding Status

**Spec:** [Annual VA spec](../spec.md), stories 28, 35.
**Release:** 1
**What to build:** An SMO sees every Finding read from their reports and proposes a Finding Status for each, supplying the field that status requires; the history of proposals is kept.
**Blocked by:** 14 (Dependency-Check parser (XML and JSON)), 18 (Attach PDF or HTML reports as supporting evidence)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 16.5 developer days across stories 28 (9.5*), 35 (7.0*); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/submissions/{id}/findings` supports filters (report type, severity, review state, needs attention) and returns identifier, package or rule, severity, confidence, source files, occurrence count, whether the check did not complete, current proposal and review state.
- [x] `POST /api/findings/{id}/proposals` (SMO, Draft or Returned for Rework) accepts Remediated with evidence, False Positive with justification, Risk Accepted with reason, approver name and approver role plus an optional approval memo (an evidence file uploaded through the supporting-evidence path), Remediation Planned with plan and a future target date; a missing required field is a 400 problem naming the field.
- [x] Each proposal is a new immutable row; the Finding's current proposal changes and its review state resets to PENDING; the action is audited without the text.
- [x] The prepare screen shows the spreadsheet-style list with per-row status select and the required field.
- [x] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-17)

**Implemented** (commits `5971fd9`, `a682c6e`, `41650d4`, `b98d9c0`, `7e2b296`): `GET /api/submissions/{id}/findings` takes the filters `reportType`, `severity` (repeated or comma-separated), `reviewState` and `needsAttention`, returns the number that match as the total, and each Finding carries `reviewState` and `currentProposal` beside the existing fields; a bad filter value is a 400. `POST /api/findings/{id}/proposals` is for the SMO of the System in Draft or Returned for Rework and takes Remediated (`evidence`), False Positive (`justification`), Risk Accepted (`reason`, `approverName`, `approverRole`, optional `approvalMemoFileId`) and Remediation Planned (`plan`, `targetDate`); a missing, unused, too long or malformed field is a 400 `invalid-field` problem with a `field` property naming it (a field the status does not use is refused, not dropped). A Cyber Manager, VA POC or Auditor gets 403, a stranger SMO or a missing Finding the same 404, a wrong state 409. Each proposal is a new row in `finding_status_proposal` (V14); a trigger forbids UPDATE, DELETE and TRUNCATE on it; the Finding's `current_proposal_id` moves and `review_state` resets to PENDING; the action is audited as `STATUS_PROPOSED` with ids, the status code and the memo file id, never any text. `GET /api/findings/{id}/proposals` (added so Seam 1 can prove the history is kept) is audited for every role as `FINDING_PROPOSALS_VIEWED`. A Finding with no proposal has a null review state; `needsAttention` means no proposal yet, or Disputed. Free text is capped at 4000 characters (names and roles 200, counted in code points) and cleaned through the one shared rule in `parser/ReportText` (control, bidi, format, tag, private-use, unassigned and surrogate characters removed), a field left empty by the cleaning is missing, and every free-text input carries its `ClassifiedField` label. The prepare screen has a Findings section: a spreadsheet-style table with a per-row status select and the fields the chosen status requires, server errors marking the input. ADR 0027 records the design; the README is amended.

**Decisions made (docs/agents/reviewer-decisions.md, 2026-10-17)**
- An approval memo is an ordinary EVIDENCE file under any Scan Report of the VA Submission, READY, counted against that report's 20-file cap; no kind of its own.
- A Remediation Planned target date is after today in Singapore and at most 10 years ahead (`ProposalFields.MAX_YEARS_AHEAD`).
- The proposal table is append-only for every connection; ticket 49 replaces the trigger, and a line naming the trigger and the foreign-key deletion order was added to ticket 49.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 660 tests, 0 failures, ArchUnit and JaCoCo met, after the correction pass (658 before it); the build check's own clean run in a copy of `backend/` passed on the final tree. Front end `npm run check`: passed.
- Reviewer: first pass Must-fix 0 with three human decisions (answered (a), (a), (a)); after the correction pass Must-fix 0, Human decisions 0.
- `/code-reviewer` final gate, first run FAIL on one High (thermo-nuclear H1: `ProposalFields` had a second, weaker copy of the character cleaner, so tag characters, U+2060 to U+2064, soft hyphen, private-use and unassigned code points were stored); fixed by one rule in `ReportText` used by the parsers and the proposals, with Seam 1 tests. Second run PASS, no Critical or High finding (Medium and Low findings remain). Report: `artifacts/code-reviewer/19-findings-list-and-propose-status-compliance.html`.
- im8 tracked deferrals: as-12 production scanner adapter and file store (ticket 51); lm-16 meters and the pm-6 OpenAPI file (ticket 52); retention of the proposal table (ticket 49).
- 42 mutations in isolated copies of `backend/` (35, then 7); all killed except one equivalent (the memo's `kind = 'EVIDENCE'` condition, covered by the evidence-never-parsed check) and one race (no row lock on the Finding) that Seam 1 cannot prove. One real hole was found and fixed on the way: a VA POC who is also the SMO of another System could propose on any System's Finding. Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open**
- No browser run of the Findings UI; the gates are type, build and lint only. Not built, as scoped: the submit rule "every Finding has a status", filter chips, bulk bar and progress bar (ticket 20), review (ticket 28), Playwright scenarios (ticket 58).
- The Findings section copy says "Every Finding needs a status before you can submit", but no submit rule exists yet.
- Proposing and the file upload check the submission state without a row lock, so a concurrent submit could race; ticket 25 should lock the submission row.
- Some blank-looking characters survive cleaning (U+3164 Hangul filler, U+2800 braille blank, U+034F, U+115F and U+1160), so a required field typed only of them passes as non-empty; the rule also splits joined emoji and some Indic and Persian text, and "unassigned" depends on the JDK's Unicode version.
- Medium and Low findings remain open in the compliance report, among them: split `FindingStore` and `Findings`, one field table for the status rules across Java, SQL and the screen with a parity test, the PostgreSQL CHECK-failure message logged with its cause, and `FOR UPDATE` taken before the ownership check.
- `current_proposal_id` has no check that the proposal belongs to the same Finding (only the application sets it). The trigger has no retention bypass (ticket 49).
