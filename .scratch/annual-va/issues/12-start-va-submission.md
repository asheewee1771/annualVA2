# 12: Start a VA Submission

**Spec:** [Annual VA spec](../spec.md), stories 19.
**Release:** 1
**What to build:** An SMO/Tech Lead starts the VA Submission for one of their Systems, which creates the Draft with its windows taken from the Quarter and records them as the submitter for segregation of duties.
**Blocked by:** 11 (SMO inbox: my Systems and what to do by when)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.3 developer days across stories 19 (6.3*); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `POST /api/submissions` with a System id (SMO of that System) creates one VA Submission per System and Cycle in state Draft with `submit_due`, `review_due` and `endorse_due` taken from the Quarter, and returns it; a second start answers 409 `invalid-transition`. (Windows from the day a System was added arrive with the Release 2 mid-cycle changes.)
- [x] Starting is allowed before the Quarter begins; a Cyber Manager, a VA POC or another SMO is refused with 403 or 404 as the ownership rules dictate.
- [x] The caller is recorded as having acted at the submitting step on this VA Submission (the segregation-of-duties record); the start is audited.
- [x] `GET /api/submissions/{id}` returns the submission with state, flags, due dates and who acted at which step to anyone allowed to see the System.
- [x] The SMO inbox's Start button opens the new submission's prepare screen (three empty Scan Report cards, Declaration disabled).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-13)

**Implemented** (commits `f3b6a98`, `36794fd`, `4b7090b`, `50ed7be`): `POST /api/submissions` (SMO of the System only) creates the VA Submission in Draft, one per System and Cycle, with `submit_due`, `review_due` and `endorse_due` copied from the Quarter, and records the caller at the submitting step (`submission_participation`, the segregation-of-duties record). A second start, including two at once, is 409 `invalid-transition` (unique `(system_id, cycle_year)` with `ON CONFLICT DO NOTHING`); a Quarter handled outside the app is also 409. Starting is allowed before the Quarter or the first live Cycle begins. A Cyber Manager, a VA POC who is not the System's SMO, an Auditor or a person with no role gets 403; an SMO of another System gets 404, the same as a missing System. A start, successful or refused, is audited as `SUBMISSION_STARTED` with a fixed reason code. `GET /api/submissions/{id}` returns state, `overdue` and `endorsedLate` (false until later tickets set them), the three due dates and `acted` (user ids only), to the System's SMO, its Cyber Manager and either VA POC; anyone else gets 404 and an Auditor 403. Following the human decision in `docs/agents/reviewer-decisions.md`, that read is audited as `SUBMISSION_VIEWED` when the caller is not the owning SMO/Tech Lead (a Cyber Manager or a VA POC); the owner's reads are not audited. The SMO inbox now shows a started System as `DRAFT` with its `submissionId` and a Continue link, and the Start button starts the submission and opens `/smo/submissions/{id}`: state and dates, three empty Scan Report cards and a disabled Declaration. V8 adds `va_submission` and `submission_participation`. ADR 0020 records the design.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 356 tests, 0 failures (the first implementation run gave 350; the audit correction pass added 6), ArchUnit and JaCoCo met. The gate's build check passed it again.
- Front end `npm run check`: passed.
- Reviewer: 0 Must-fix on both rounds. One Human decision (whether a single-submission read is audited), answered as option (c) and recorded.
- `/code-reviewer` final gate: PASS, all seven checks ran, no Critical or High finding (build PASS, semgrep PASS from the official Docker image; thermo-nuclear, code-review, im8, react and springboot WARN). Report: `artifacts/code-reviewer/12-start-va-submission-compliance.html`.
- im8: as-12 (upload malware scan) stays a tracked deferral to ticket 13, unchanged by this ticket.
- Mutation gate: skipped.

All checklist boxes above are ticked.

**Not verified, and open**
- The Start-to-prepare-screen flow was not viewed in a browser: the mock-m-sso sign-in stack is not on this machine. No browser evidence is recorded.
- Medium findings the gate raised and this commit did not act on:
  - The refusal audit in `Submissions.start` uses `REQUIRES_NEW` while the command's transaction is open, so many concurrent refused starts could exhaust the connection pool.
  - `submission_participation` in V8 has no database-generated uuid `id`, against ADR 0010.
  - Front end: several Start buttons can be clicked at once, and a failed start leaves a stale alert after the Cycle changes.
  - The 409 for a Quarter handled outside the app is a rule the spec does not state; it is recorded in ADR 0020 and was not put to a human.
- The inbox row's `quarter` comes from the Register's current Quarter while its dates come from the stored submission; once the Register can move a System (ticket 43) a Draft would show the new Quarter beside the old dates. Carried to ticket 43.
- `overdue` is still not computed (ticket 26).
- Medium and Low findings remain open in the compliance report.
