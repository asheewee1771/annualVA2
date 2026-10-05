# 08: Upload the System Register and preview every change

**Spec:** [Annual VA spec](../spec.md), stories 2, 12, 14.
**Release:** 1
**What to build:** A VA POC uploads a filled-in template and sees, before anything changes, every System that would be added, updated or retired, the rows that are blocked and why, and which SMO emails are not yet linked to a sign-in. Nothing changes until the apply (next tickets), which is the step-up control the permissions matrix places on the Register upload.
**Blocked by:** 07 (Download the System Register template), 06 (VA Cycle and Quarter calendar)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 13.0 developer days across stories 2 (10.0*), 12 (2.0), 14 (1.0); `*` marks the critical path. See `reports/dev-report.md`.
**Template note (2026-10-05):** ticket 07 fixed the five header texts in `RegisterTemplate.HEADERS` (upload must match them as written), the Quarter notation `Q1` to `Q4`, and an example row `EXAMPLE-001`. Block a System ID that starts `EXAMPLE-` so an unchanged example row cannot be uploaded as a real System. See ADR 0016.

- [ ] `POST /api/system-register/uploads` (VA POC, multipart, `.xlsx` up to 2 MB, content sniffed) parses the template and returns a preview id plus rows classified ADD, UPDATE, RETIRE or NO CHANGE by System ID, each with before and after values.
- [ ] A row with no Cyber Manager, with the System's own SMO as its Cyber Manager, with an invalid email or Quarter, or with a duplicate System ID is marked Blocked with a reason; a preview with any Blocked row cannot be applied.
- [ ] A System whose SMO email matches no sign-in is flagged Unlinked SMO in the preview and later in the Register table.
- [ ] The preview expires after one hour, is audited, and never echoes file content in an error.
- [ ] The Register screen shows the upload, the grouped preview (Add, Update, Retire, Blocked) and the Unlinked SMO flag.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
