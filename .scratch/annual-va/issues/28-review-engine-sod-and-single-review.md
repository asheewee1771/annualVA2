# 28: Review engine 1: segregation of duties and single confirm or Dispute

**Spec:** [Annual VA spec](../spec.md), stories 50 (part), 58.
**Release:** 1
**What to build:** A Cyber Manager confirms each proposed Finding Status or marks it Disputed with a comment, including the not-applicable justifications; anyone who acted at another step on the same VA Submission is blocked from reviewing it.
**Blocked by:** 27 (Cyber Manager inbox and Quarter board)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 11.0 developer days across stories 50 (6.0), 58 (5.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/findings/{id}/reviews` (Cyber Manager assigned to the System, submission In Review) records CONFIRMED or DISPUTED with a comment on the current proposal and records the reviewing step; `POST /api/submissions/{id}/reports/{type}/review` does the same for a not-applicable justification.
- [ ] The segregation-of-duties guard refuses a command at one step when the caller acted at another step on that submission, and treats the System's assigned SMO as the submitter and its assigned Cyber Manager as the reviewer even before they act; refusals are 403 `sod-conflict` and audited.
- [ ] A Finding is CONFIRMED only while its current proposal is the reviewed one; reviews are audited without comment text.
- [ ] The review screen shows per-Finding Confirm and Dispute with the comment field, and Manually Entered Findings and not-applicable justifications clearly marked.
- [ ] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
