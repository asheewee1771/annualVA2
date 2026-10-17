# 49: Retention: disposal list, approval and hard delete

**Spec:** [Annual VA spec](../spec.md), stories 87.
**Release:** 1
**What to build:** Records due for disposal (Cycle plus 3 years, superseded files once Endorsed, expired exports) are listed; a VA POC approves the list with a fresh sign-in; the app hard-deletes them and logs it.
**Blocked by:** 44 (Management summary export (Excel), watermarked, no vulnerability detail), 21 (Replace a Scan Report file without losing work)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 14.5 developer days across stories 87 (14.5); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] A daily job computes due records and writes a DRAFT disposal list with items (submissions, files, superseded files, exports, audit ranges) and reasons; `GET /api/disposal-lists` shows it.
- [ ] `POST /api/disposal-lists/{id}/approve` (VA POC, step-up) marks it APPROVED; the executor deletes file-store objects then records, removes audit entries only within the approved range, and writes one tombstone audit entry with counts and the list id; any other removal before retention is a soft delete.
- [ ] The executor also disposes of `finding_status_proposal`, which has an append-only trigger (V14, ADR 0027) with no bypass: a later migration replaces the trigger, and the delete runs in foreign-key order (clear `finding.current_proposal_id`, delete the proposals, then the Findings and files); a Seam 1 test proves proposals are gone after an approved disposal.
- [ ] The Disposal screen shows the list for review with Approve (step-up); Seam 1 tests move the clock past Cycle plus 3 years and prove the list, the approval, the deletion and the tombstone.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
