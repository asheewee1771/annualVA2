# 23: Confirm scan date and target; enforce the age rules

**Spec:** [Annual VA spec](../spec.md), stories 25, 26.
**Release:** 1
**What to build:** The scan date and target pre-filled from each file are shown for the SMO to confirm or correct; a future-dated file is rejected at upload, and a file older than 90 days at submission time blocks submitting with a message naming that file.
**Blocked by:** 14 (Dependency-Check parser (XML and JSON))
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.3 developer days across stories 25 (4.0), 26 (2.3); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `PUT /api/submissions/{id}/files/{fileId}/metadata` lets the SMO confirm or edit scan date and target; an edited target is flagged, never blocked; Burp dates are shown as export time only.
- [ ] A file whose scan date is after today's business date is rejected as REJECTED_FUTURE_DATED during the pipeline; the pipeline order test now covers parse, pre-fill and age after the schema check.
- [ ] The readiness check reports every file older than 90 days at today's business date by name; the age is evaluated against the controllable clock so tests move the clock rather than rewriting samples.
- [ ] The Scan Report card shows the scan date and the confirmed target, both editable, with an edited target marked.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
