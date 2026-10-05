# 35: VA POC return with comments; resubmission goes through the Cyber Manager

**Spec:** [Annual VA spec](../spec.md), stories 66, 63.
**Release:** 1
**What to build:** A VA POC returns a submission to the SMO with comments to challenge something the Cyber Manager confirmed; the SMO's resubmission goes through the Cyber Manager again, who re-reviews only what changed, so the chain of confirmation stays whole.
**Blocked by:** 34 (Endorse a VA Submission), 32 (Review engine 3: re-review only what changed)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 8.6 developer days across stories 66 (6.6), 63 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/submissions/{id}/return` by a VA POC (step-up, Awaiting Endorsement) moves the state to Returned for Rework, requires a comment, stores the return, audits and publishes required notifications to the SMO and the Cyber Manager; a VA POC conflicted by segregation of duties is refused with `sod-conflict`.
- [ ] Returned for Rework after a VA POC return is Overdue after `review_due` like any other; resubmitting moves to In Review and the Cyber Manager re-reviews only what changed, including after this VA POC return.
- [ ] The endorsement view offers Return with a comment, and the SMO's rework queue shows the VA POC's comment.
- [ ] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
