# 25: Tick the Declaration and submit for review

**Spec:** [Annual VA spec](../spec.md), stories 40, 41.
**Release:** 1
**What to build:** Submit stays disabled until every Finding has a proposed status and the SMO has ticked the fixed Declaration; submitting stores the exact wording agreed and moves the VA Submission to In Review.
**Blocked by:** 20 (Filter chips, select-all, bulk bar and progress bar), 22 (Mark a Scan Report not applicable or enter Findings by hand), 23 (Confirm scan date and target; enforce the age rules), 15 (Secure Configuration parser: SCAP ARF and XCCDF results), 24 (Agree the Declaration text and its owner)
**Status:** closed
**Effort (orchestrator estimate):** about 9.0 developer days across stories 40 (4.0), 41 (5.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/declaration-text` returns the current version and wording (seeded; placeholder until the Declaration text ticket lands).
- [x] `POST /api/submissions/{id}/submit` (SMO, Draft or Returned for Rework) is refused with a 409 problem listing the reasons when any Finding lacks a proposal, the Secure Configuration Scan Report has neither a ready machine-readable file nor manual mode with justification, another Scan Report is neither ready nor not-applicable nor manual with justification, any file is older than 90 days (naming it), future-dated or still scanning (including a rescan replacing a file, ticket 21; decision 2026-10-18), or the Declaration is not accepted. (The Target Date Missed block arrives with Release 2 carry-over.)
- [x] A successful submit snapshots the Declaration version and text with who accepted it and when, moves the state to In Review, records the submitting step, audits the submission and publishes the event the notification tickets consume.
- [x] The prepare screen's Submit button is disabled with the reasons shown until everything is complete, and the submission becomes read-only afterwards.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-10-23)

**Implemented** (commits `ebb7c05`, `22d66b6`, `339d818`, `d0f9a0c`):
- **Declaration text.** `GET /api/declaration-text` returns `{version, text, placeholder}`. V19 seeds version 1, marked as a placeholder ("PLACEHOLDER WORDING, NOT YET AGREED."). The current version is the highest, and `declaration_text` is append-only. Ticket 24's agreed wording goes in as a new version by migration, with no code change.
- **Submit.** `POST /api/submissions/{id}/submit` takes `{declarationAccepted, declarationVersion}`, for the SMO/Tech Lead only: 404 for another System's VA Submission or a missing one, 403 for other roles.
  - Outside Draft or Returned for Rework it is 409 `invalid-transition`.
  - Otherwise any blocker gives one 409 `not-ready` with every reason in order: `files-scanning`, `report-missing` (per Scan Report: Secure Configuration needs a parsed file or manual mode; the others also accept not applicable), `findings-unproposed`, `files-too-old`, `files-undated`, `files-future-dated`, then `declaration-not-accepted` or `declaration-changed`. The refusal is audited with its count. Target Date Missed waits for Release 2.
  - A successful submit, in one transaction: snapshots the Declaration version and text with who accepted it and when, in the append-only `declaration` table; moves the state to In Review with `submitted_at` and `submitted_by`; records the SUBMIT step; writes `SUBMISSION_SUBMITTED` with `declaration_version`; and publishes a `SubmissionEvent`.
- **Locking.** Submit holds the VA Submission row (`FOR NO KEY UPDATE`) and re-checks state and readiness under it. Every write to prepared content takes the row `FOR SHARE` through `Submissions.lockForEdit`, which returns an `EditTarget` that the store writes require. An ArchUnit rule enforces it.
- **Screen.** A Declaration card shows the wording, flagged as a placeholder, with a classified tick box. Submit stays disabled until readiness has no blockers and the box is ticked, with every reason listed. After submit the screen is read-only through one `<Editable>` wrapper: upload, Replace and manual entry are shown disabled rather than hidden. The SMO inbox shows In Review and Returned for Rework.
- **Docs.** ADR 0032 records the design. ADRs 0020, 0028, 0030 and 0031 and the README are amended.

**Decisions**
- Ticket 24 is not a blocker, per the user on 2026-10-23: "Run do-work on ticket 25 with the placeholder Declaration seed; I will agree ticket 24's wording and owner with the PO and cyber team separately."
- The submit command stays in `submission`, per ADR 0032. The thermo-nuclear Medium suggesting a move into `finding` was not taken.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 767 tests, 0 failures, ArchUnit and JaCoCo met. Front end `npm run check`: passed.
- Reviewer: Must-fix 0, no human decisions. Its low notes were taken in the correction pass: the submit Javadoc, readiness reloaded after a refused submit, and the post-submit lock-out test now covering manual entry, bulk-set and rescan.
- `/code-reviewer` round 1: FAIL on two thermo-nuclear Highs (the edit lock was a convention copied six times with an unlocked bypass; read-only was a boolean passed through about 44 places). After one correction pass, round 2 is a PASS: build and semgrep PASS; thermo-nuclear, code-review, im8, react and springboot WARN; no Critical or High finding. Semgrep's one High is in `TestEndpoints.java`, which this ticket did not change, and is not counted, as in ticket 22. Report: `artifacts/code-reviewer/25-declaration-and-submit-compliance.html`.
- Selective mutation: 10 mutants. Six were killed on the first run. Four survived (ownership on a refused submit, exactly one file still scanning, exactly one unproposed Finding, the highest Declaration version), and stronger Seam 1 tests now kill all 10. Production code was unchanged by this. Report: `artifacts/mutation-testing/25-declaration-and-submit-mutation.md`.
- Speed rules approved on 2026-10-17 applied: the build check reused the implementer's verify log, and round 2 reran only thermo-nuclear plus build.

All checklist boxes above are ticked.

**Not verified, and open**
- The Declaration wording is a placeholder until ticket 24 (ready-for-human) agrees it and its owner.
- code-review, semgrep, im8, react and springboot were not rerun after the correction pass, which refactored the edit lock and the read-only screen.
- The row locks cannot be shown by Seam 1 tests. ArchUnit guarantees every write path takes the lock, but not the timing.
- No browser run, because sign-in needs `../mock-m-sso`.
- `SubmissionEvent` has no production consumer yet (tickets 36 and 37). There is no segregation-of-duties check beyond recording SUBMIT (ticket 28). The Returned for Rework inbox entry is due on `submitDue` for now (ticket 31).
- Medium and Low findings remain open in the compliance report.
