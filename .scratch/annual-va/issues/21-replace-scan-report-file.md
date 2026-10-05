# 21: Replace a Scan Report file without losing work

**Spec:** [Annual VA spec](../spec.md), stories 39.
**Release:** 1
**What to build:** An SMO replaces one file of a Scan Report with a rescan. Only that file's occurrences are compared: matching Findings keep their status and history, new ones are added, and a Finding the rescan no longer reports anywhere becomes Remediated with the rescan as evidence.
**Blocked by:** 19 (List Findings and propose a Finding Status)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 10.5 developer days across stories 39 (10.5); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/submissions/{id}/files/{fileId}/replace` runs the new file through the whole pipeline and, once parsed, compares identity keys against the old file's occurrences only.
- [ ] Kept keys swap occurrences and keep proposals and reviews; keys absent from the new file lose those occurrences, and a Finding left with no occurrence gets a new proposal Remediated with evidence naming the rescan file and date, proposed as RESCAN, review state PENDING; new keys create new Findings.
- [ ] The old file is marked superseded (kept until the submission is Endorsed, then listed for disposal), the replacement is audited, and the Scan Report card offers Replace per file.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
