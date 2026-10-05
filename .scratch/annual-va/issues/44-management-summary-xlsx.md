# 44: Management summary export (Excel), watermarked, no vulnerability detail

**Spec:** [Annual VA spec](../spec.md), stories 81, 86.
**Release:** 1
**What to build:** A VA POC requests the management summary with a fresh sign-in, it is generated in the background and downloaded as Excel with its classification watermark, containing counts and a by-System table but no vulnerability detail.
**Blocked by:** 34 (Endorse a VA Submission), 33 (VA POC Quarter board and Cycle matrix)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 5.3 developer days across stories 81 (3.3), 86 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/exports` with type MGMT_SUMMARY and format XLSX (VA POC, step-up) queues an export; it is produced in the background with counts by Quarter and state, Endorsed Late, and a by-System table (SMO, state, Overdue, number of open Deviations), excluding Withdrawn submissions and Quarters handled outside the app, stored encrypted with a 24-hour expiry.
- [ ] `GET /api/exports/{id}` reports status; `GET /api/exports/{id}/download` (requester only) serves the attachment and audits the download; the file carries its classification in header, footer and first row: Confidential when it covers the complete set (all Systems of a Quarter or Cycle), otherwise Restricted / Sensitive-Normal (assumption A-1).
- [ ] A sentinel Finding never appears in the export; the Exports screen requests, polls and downloads.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
