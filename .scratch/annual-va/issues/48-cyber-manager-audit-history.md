# 48: Cyber Manager audit history

**Spec:** [Annual VA spec](../spec.md), stories 85.
**Release:** 1
**What to build:** A Cyber Manager reads the audit history of the VA Submissions they reviewed, on Systems still assigned to them.
**Blocked by:** 28 (Review engine 1: segregation of duties and single confirm or Dispute), 05 (Append-only audit log with refused-request logging)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 3.3 developer days across stories 85 (3.3); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/audit-entries?submission=` for a Cyber Manager returns entries only for submissions where they acted at the reviewing step on Systems still assigned to them; any other submission answers 404.
- [ ] The audit log viewer is available on the Cyber Manager screens with the same filters and no Finding detail.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
