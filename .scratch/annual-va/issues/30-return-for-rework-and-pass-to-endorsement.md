# 30: Return for rework or pass to endorsement

**Spec:** [Annual VA spec](../spec.md), stories 55, 57.
**Release:** 1
**What to build:** A Cyber Manager returns a submission with its Disputed Findings attached while confirmed Findings stay confirmed, or, once every Finding and justification is confirmed, passes it to the VA POCs.
**Blocked by:** 28 (Review engine 1: segregation of duties and single confirm or Dispute)
**Status:** closed
**Effort (orchestrator estimate):** about 10.0 developer days across stories 55 (6.0), 57 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/submissions/{id}/return` (Cyber Manager, In Review, at least one DISPUTED Finding or not-applicable justification) moves the state to Returned for Rework, stores the return with comment, keeps CONFIRMED Findings confirmed, audits and publishes the event for the required notification to the SMO.
- [x] `POST /api/submissions/{id}/pass-to-endorsement` (Cyber Manager, every Finding and justification CONFIRMED) moves the state to Awaiting Endorsement, audits and publishes the event for both VA POCs; with anything unconfirmed it answers 409 listing what is pending.
- [x] The review screen shows Return for rework and Pass to endorsement with the preconditions explained.
- [x] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [x] Return and pass to endorsement are review-step commands: they take the segregation-of-duties guard of ADR 0035 (`Submissions.lockForReview`, or the same rule with the row held exclusively) and record the `REVIEW` step.
- [x] Decide how a VA POC counts for segregation of duties before an endorsing step exists (here or in ticket 34, whichever adds it). `Submissions.assignedAtAnotherStep` reads `guard.scope(need).ids()` and ignores `SystemScope.all`, and a VA POC's `Need.ENDORSE` scope is `all` with no ids. So adding an `ENDORSE` step to `SubmissionStep` as it stands would treat no VA POC as assigned. The rule must say explicitly whether holding the VA POC role counts as assigned to the endorsing step of every System, or only acting does (spec: two VA POCs, no delegation, a conflicted one leaves the item to the other).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-26)

Built and merged by the implement-spec run into `integration/annual-va-r1` (merge commit `61afa2e`).

**Implemented:** Return for rework (needs a Dispute, keeps confirmations) and pass to endorsement (lists what blocks it, sets the reviewed date); returns record; readiness gains `review`. ADR 0039, migration V23 (renumbered into one sequence on 2026-10-25, see the README's migration table); README section added.

**Verification performed**
- Back end `mvnw -B -ntp clean verify` on the ticket's branch after merging the integration tip: 873 tests, 0 failures, ArchUnit and JaCoCo met; front end `npm run check` passed. Per-ticket TDD with Seam 1 tests through the HTTP API on a real PostgreSQL.
- `/code-reviewer` on the ticket's diff: no Critical or High finding left. Report: `artifacts/code-reviewer/30-return-for-rework-and-pass-to-endorsement-compliance.html`.
- Decisions the ticket left to a human were built as the recommended option and accepted by the user on 2026-10-25 (`docs/agents/reviewer-decisions.md`, ADR 0039); the whole-branch review's fixes and decisions Q72 to Q77 followed on 2026-10-26.

All checklist boxes above are ticked.

**Not verified, and open**
- No screen of this run was opened in a browser: sign-in needs `../mock-m-sso` (ticket 01) and the Playwright scenarios belong to ticket 53.
- Medium and Low findings from the compliance report remain open there; the per-ticket review was run in the approved fast mode (only the failing checks plus build were rerun after a correction).
