# 19: List Findings and propose a Finding Status

**Spec:** [Annual VA spec](../spec.md), stories 28, 35.
**Release:** 1
**What to build:** An SMO sees every Finding read from their reports and proposes a Finding Status for each, supplying the field that status requires; the history of proposals is kept.
**Blocked by:** 14 (Dependency-Check parser (XML and JSON)), 18 (Attach PDF or HTML reports as supporting evidence)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 16.5 developer days across stories 28 (9.5*), 35 (7.0*); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/submissions/{id}/findings` supports filters (report type, severity, review state, needs attention) and returns identifier, package or rule, severity, confidence, source files, occurrence count, whether the check did not complete, current proposal and review state.
- [ ] `POST /api/findings/{id}/proposals` (SMO, Draft or Returned for Rework) accepts Remediated with evidence, False Positive with justification, Risk Accepted with reason, approver name and approver role plus an optional approval memo (an evidence file uploaded through the supporting-evidence path), Remediation Planned with plan and a future target date; a missing required field is a 400 problem naming the field.
- [ ] Each proposal is a new immutable row; the Finding's current proposal changes and its review state resets to PENDING; the action is audited without the text.
- [ ] The prepare screen shows the spreadsheet-style list with per-row status select and the required field.
- [ ] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
