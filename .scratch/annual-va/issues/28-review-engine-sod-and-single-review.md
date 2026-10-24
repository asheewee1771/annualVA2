# 28: Review engine 1: segregation of duties and single confirm or Dispute

**Spec:** [Annual VA spec](../spec.md), stories 50 (part), 58.
**Release:** 1
**What to build:** A Cyber Manager confirms each proposed Finding Status or marks it Disputed with a comment, including the not-applicable justifications; anyone who acted at another step on the same VA Submission is blocked from reviewing it.
**Blocked by:** 27 (Cyber Manager inbox and Quarter board)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 11.0 developer days across stories 50 (6.0), 58 (5.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/findings/{id}/reviews` (Cyber Manager assigned to the System, submission In Review) records CONFIRMED or DISPUTED with a comment on the current proposal and records the reviewing step; `POST /api/submissions/{id}/reports/{type}/review` does the same for a not-applicable justification.
- [x] Decide with the human what text is kept beside a review of a not-applicable justification: the justification is overwritten in place and a changed text resets the review to PENDING (ADR 0030; decision 2026-10-18), so after a Dispute and a rewrite the disputed wording is gone unless this ticket stores it with the review.
- [x] The segregation-of-duties guard refuses a command at one step when the caller acted at another step on that submission, and treats the System's assigned SMO as the submitter and its assigned Cyber Manager as the reviewer even before they act; refusals are 403 `sod-conflict` and audited.
- [x] A Finding is CONFIRMED only while its current proposal is the reviewed one; reviews are audited without comment text.
- [x] The review screen shows per-Finding Confirm and Dispute with the comment field, and Manually Entered Findings and not-applicable justifications clearly marked.
- [x] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Reviewer log (do-work reviewer, rounds 1-2)

## Findings

| Finding | Required action | Status |
| --- | --- | --- |
| A review of a not-applicable Scan Report's text does not name the version the Cyber Manager read, so a screen loaded before a Return for Rework, a change of that text and a resubmit can Confirm text the Cyber Manager never saw. The 2026-10-18 decision accepted overwriting because a Confirmed review never refers to unseen text, and a Finding's review already closes the same gap with proposalId and 409 proposal-changed. Unreachable until ticket 30 adds Return for Rework, but the request shape changes when it is closed.<br><br><details><summary><strong>Verify in</strong></summary><br><code>Reviews.reviewReport</code><br><code>ReviewFields.ofJustification</code><br><code>ReviewScreen.tsx NotApplicableSection</code><br><code>scanReports.ts reviewScanReport</code></details> | Close it here, in the same way as proposalId: the screen sends the version of the justification it showed (a counter on report_slot, increased wherever the review is put back to PENDING, or a SHA-256 of the wording). Under the slot lock, a different value answers 409 justification-changed and is audited REFUSED with that reason, never echoing the text. Add a Seam 1 test: Dispute, Return for Rework, rewrite, resubmit, then a stale Confirm gets 409 and the state stays PENDING. | Resolved |
| Ticket 28 adds two endpoints, V21 with two append-only tables, a new screen and route, and a stricter segregation-of-duties rule that now refuses start and submit with 403 sod-conflict. No ADR or README records any of it. Docs that are now wrong: ADR 0034 item 4 and README line 164 say sodConflict reads participation only and name Submissions.actedAtAnotherStep, which no longer exists. README line 176 says later tickets check participation. The javadoc and TypeScript doc of sodConflict, the audit-action docs of start and submit, and the inbox flag text ('You acted at another step') are wrong for a Cyber Manager who is assigned as SMO but has not acted.<br><br><details><summary><strong>Verify in</strong></summary><br><code>adr (no ADR for ticket 28)</code><br><code>ADR 0034 item 4</code><br><code>ADR 0030 item 3</code><br><code>ADR 0032</code><br><code>README.md lines 164, 168, 176</code><br><code>CmInbox.Item and QuarterBoard.Card javadoc</code><br><code>dashboards.ts sodConflict</code><br><code>ReviewFlags.tsx flag text</code><br><code>AuditAction SUBMISSION_STARTED and SUBMISSION_SUBMITTED</code></details> | Write ADR 0035 for the review engine. It should cover the one SoD rule (acted at, or assigned to, another step), the check order, the ReviewTarget lock, review rows as append-only history tied to a proposal, the justification copy (or whatever the human chooses), the comment limits and audit details, and what is deliberately not done (SoD on edits, batch review, return and pass). Amend ADR 0034 item 4, ADR 0030 item 3 and ADR 0032 (sod-conflict on submit), and ticket 12's start behaviour where it is documented. Add README sections for both endpoints, the 403 on start and submit, and the /cyber-manager/submissions/{id} screen. Correct the stale sodConflict wording in code docs and the flag text (for example 'You acted at, or are assigned to, another step: you cannot review it'). | Resolved |
| The claim is that every role other than the System's Cyber Manager gets 403 from both new write endpoints (IM8 ac-1), but the tests cover only the SMO/Tech Lead and a VA POC. An Auditor, a person with no role and an anonymous caller (401) are not tested, whereas the ticket 27 endpoints were tested for all of them.<br><br><details><summary><strong>Verify in</strong></summary><br><code>FindingReviewTest.onlyTheCyberManagerOfTheSystemReviewsItsFindings</code><br><code>ReportReviewTest.onlyTheCyberManagerOfTheSystemReviewsItAndOnlyInReview</code></details> | Add an Auditor (403), a no-role user (403) and an anonymous call (401) to both access tests. Assert that nothing is kept and no reviewing step is recorded. | Resolved |
| The 2026-10-18 decision and ticket 28's acceptance line leave the human to decide what text is kept beside a review of a not-applicable justification. The implementer chose option (a) provisionally: each report_review row copies the justification as it was reviewed.<br><br><details><summary><strong>Verify in</strong></summary><br><code>V21__review.sql report_review.justification</code><br><code>ReviewStore.INSERT_REPORT_REVIEW</code><br><code>ADR 0030 item 3</code><br><code>reviewer decision 2026-10-18</code></details> | Human decision: (a) keep as built, each review row copies the reviewed wording (at most 4000 characters, append-only, same handling as the justification) [reviewer recommends]; (b) keep only the decision and comment, so the Disputed wording is lost after a rewrite; (c) make justifications an append-only history and have reviews point at a version, which reworks ADR 0030 and ticket 22. | Resolved |


## Human decisions

| Area | Decision | Chosen action | Reuse when |
| --- | --- | --- | --- |
| backend finding reviews, V21 report_review, ADR 0030 item 3 | The justification is overwritten in place, so a disputed wording would be lost after a rewrite (KB unavailable). | Do not follow KB recommendation: no KB guidance; user chose (a) on 2026-10-24, each review row keeps a copy of the justification it reviewed. | Any review of free text that can be rewritten after it is reviewed. |


## Full do-work log

Open locally: `artifacts/do-work/28-review-engine-sod-and-single-review.html`

Includes reviewer findings and human decisions.

### Verification (2026-10-24)

**Implemented** (commits `ca614a5`, `ca5fc54`, `204d5b1`, `aac065e`):
- **Reviews.** `POST /api/findings/{id}/reviews` and `POST /api/submissions/{id}/reports/{type}/review` (a Cyber Manager assigned to the System, VA Submission In Review) record CONFIRMED or DISPUTED with a comment, and record the REVIEW step.
  - A review is an append-only row (V21 `finding_review`, `report_review`) that names what it read. A Finding review names the current proposal (409 `proposal-changed` otherwise). A justification review names the justification's version, a counter on `report_slot` (409 `justification-changed` otherwise), and keeps a copy of the wording it reviewed.
  - Checks run in this order: who may see it (404), segregation of duties (403), state (409), fields (400), then what was read. Reviews and refusals are audited with the decision, report type and fixed reasons only, never the comment.
  - The comment is at most 4000 characters after cleaning, required to Dispute and optional to confirm, and carries a classification label.
- **Segregation of duties.** One rule, `SegregationOfDuties.require`, refuses a step to anyone who acted at, or is assigned to, another step on the VA Submission. It runs at start, submit and review (403 `sod-conflict`, audited), and the Cyber Manager inbox marker reads the same rule. An ArchUnit rule makes every command that records a step ask it. A scope over every System (a VA POC's) is an explicit error until the endorsing step decides how it counts.
- **Screen.** `/cyber-manager/submissions/{id}` shows Confirm and Dispute per Finding and per not-applicable justification, with Manually Entered Findings and not-applicable reports marked and the latest review shown.
- **Docs.** ADR 0035 records the design. ADRs 0020, 0027, 0030, 0032 and 0034 and the README are amended, and ticket 30 gained a note.

**Decision made (docs/agents/reviewer-decisions.md, 2026-10-24)**
- A review of a not-applicable justification keeps a copy of the wording it reviewed (option (a)).

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 826 tests, 0 failures, ArchUnit and JaCoCo met. Front end `npm run check`: passed.
- Reviewer: round 1 Must-fix 3 and one human decision; round 2 Must-fix 0. Its log is above. The round-1 Must-fix items were the stale-screen gap on a justification review, no ADR or README, and missing role tests.
- `/code-reviewer` round 1: FAIL on two blockers (segregation of duties applied in three shapes; a raw U+202E in a test). After one correction pass, round 2 reran all seven checks and is a PASS: build PASS; thermo-nuclear, code-review, semgrep, im8, react and springboot WARN; no Critical or High finding. Report: `artifacts/code-reviewer/28-review-engine-sod-and-single-review-compliance.html`.
- Speed rules approved on 2026-10-17 applied: the build check reused the implementer's verify log.

All checklist boxes above are ticked.

**Not verified, and open**
- Selective mutation: stopped when the session ended after M01 and M02 (both killed). The run left mutant M03 in `SubmissionStore.java`; it was found and reverted, and the tree was checked against the clean build before committing. The remaining mutants were skipped at the user's request, and there is no mutation report.
- No browser run of the review screen, because sign-in needs `../mock-m-sso` (ticket 53).
- Not done on purpose: the edit paths (proposals, uploads, mode changes) do not run the segregation-of-duties check, though submit does. Batch review, return for rework and pass to endorsement are tickets 29 and 30.
- Medium and Low findings remain open in the compliance report.
