# 29: Review engine 2: confirm or Dispute a Bulk-set batch

**Spec:** [Annual VA spec](../spec.md), stories 51, 52.
**Release:** 1
**What to build:** Bulk-set Findings are grouped by batch and the Cyber Manager confirms or Disputes a whole batch at once; Manually Entered Findings stay clearly marked for closer review.
**Blocked by:** 28 (Review engine 1: segregation of duties and single confirm or Dispute)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.0 developer days across stories 51 (5.0), 52 (1.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/submissions/{id}/batches` lists batches with their status, detail summary and member counts; `POST /api/batches/{id}/reviews` applies CONFIRMED or DISPUTED (with one comment) to every member whose current proposal belongs to the batch and is PENDING, and is audited as one batch review.
- [ ] Members changed individually since the batch are left out of the batch review and listed in the response.
- [ ] The review screen groups batch members under their batch with Confirm batch and Dispute batch actions.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
