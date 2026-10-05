# 26: Overdue is a flag: soft windows, nothing locks

**Spec:** [Annual VA spec](../spec.md), stories 42, 71.
**Release:** 1
**What to build:** When a window passes without the next step, the VA Submission (or a Not Started System) is flagged Overdue, visible to everyone concerned, and the SMO can keep working.
**Blocked by:** 25 (Tick the Declaration and submit for review)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 10.0 developer days across stories 42 (6.0), 71 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] A scheduled sweep flags Not Started and Draft after `submit_due`, In Review and Returned for Rework after `review_due`, and Awaiting Endorsement after `endorse_due`, recording when the flag was set; final states and Quarters handled outside the app are skipped; running the sweep twice changes nothing more.
- [ ] An Overdue Draft can still upload, propose and submit; Seam 1 tests move the clock to week 3 and run the sweep.
- [ ] The SMO inbox and submission screens show the Overdue pill; setting the flag is audited.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
