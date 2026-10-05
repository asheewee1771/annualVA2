# 25: Tick the Declaration and submit for review

**Spec:** [Annual VA spec](../spec.md), stories 40, 41.
**Release:** 1
**What to build:** Submit stays disabled until every Finding has a proposed status and the SMO has ticked the fixed Declaration; submitting stores the exact wording agreed and moves the VA Submission to In Review.
**Blocked by:** 20 (Filter chips, select-all, bulk bar and progress bar), 22 (Mark a Scan Report not applicable or enter Findings by hand), 23 (Confirm scan date and target; enforce the age rules), 15 (Secure Configuration parser: SCAP ARF and XCCDF results), 24 (Agree the Declaration text and its owner)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 9.0 developer days across stories 40 (4.0), 41 (5.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/declaration-text` returns the current version and wording (seeded; placeholder until the Declaration text ticket lands).
- [ ] `POST /api/submissions/{id}/submit` (SMO, Draft or Returned for Rework) is refused with a 409 problem listing the reasons when any Finding lacks a proposal, the Secure Configuration Scan Report has neither a ready machine-readable file nor manual mode with justification, another Scan Report is neither ready nor not-applicable nor manual with justification, any file is older than 90 days (naming it), future-dated or still scanning, or the Declaration is not accepted. (The Target Date Missed block arrives with Release 2 carry-over.)
- [ ] A successful submit snapshots the Declaration version and text with who accepted it and when, moves the state to In Review, records the submitting step, audits the submission and publishes the event the notification tickets consume.
- [ ] The prepare screen's Submit button is disabled with the reasons shown until everything is complete, and the submission becomes read-only afterwards.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
