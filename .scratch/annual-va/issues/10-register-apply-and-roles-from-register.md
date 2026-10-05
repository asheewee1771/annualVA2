# 10: Apply a Register preview; SMO and Cyber Manager roles follow the Register

**Spec:** [Annual VA spec](../spec.md), stories 2, 3, 4, 73, 79.
**Release:** 1
**What to build:** A VA POC applies a confirmed preview with a fresh sign-in. Systems are added or updated by System ID, missing ones are retired, and the SMO and Cyber Manager grants are created from the Register so that a former SMO loses access at once.
**Blocked by:** 08 (Upload the System Register and preview every change), 09 (Step-up: a fresh sign-in before sensitive actions), 05 (Append-only audit log with refused-request logging)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 30.5 developer days across stories 2 (10.0*), 3 (8.5), 4 (5.0), 73 (5.0), 79 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/system-register/previews/{id}/apply` (VA POC, step-up) applies ADD, UPDATE and RETIRE rows in one transaction; a retired System keeps its history and is never deleted; re-applying an expired or already applied preview is refused with 409.
- [ ] For every System the apply upserts an SMO grant and a Cyber Manager grant with source REGISTER scoped to that System, by email where the person has not signed in yet; grants for people no longer named are revoked in the same transaction and their next request is refused.
- [ ] The apply is audited with counts, plus one entry per retired System and per role granted or revoked; the audit never records emails in free text.
- [ ] `GET /api/systems` (VA POC all; SMO and Cyber Manager only their Systems) lists Systems with SMO, Cyber Manager, Quarter, retired flag and the Unlinked SMO flag.
- [ ] The Register screen's Apply needs step-up and then shows the updated Register table.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
