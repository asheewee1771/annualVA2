# 42: Conditional Exemption: record the VA, confirm it, or lapse

**Spec:** [Annual VA spec](../spec.md), stories 47.
**Release:** 1
**What to build:** For an Exemption based on a future VA, the SMO records that it happened and a VA POC confirms; if it is not recorded within 3 months after the Quarter's end the System is flagged and returned to its previous state with new two-week windows, or becomes Not Endorsed once the Cycle has closed.
**Blocked by:** 40 (Request, freeze and decide an Exemption), 26 (Overdue is a flag: soft windows, nothing locks), 41 (VA Cycle lifecycle: open, overlap, close)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.5 developer days across stories 47 (6.5); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/exemptions/{id}/va-happened` (SMO) records the date and suspends the lapse; `POST /api/exemptions/{id}/confirm-va` (VA POC, step-up, `sod-conflict` for a conflicted VA POC) makes the Exemption unconditional; both work after the Cycle has closed for Q4 Systems and both are audited.
- [ ] A daily job lapses conditional Exemptions past their deadline with no recorded VA. While the Cycle is open the submission shows its previous state (Not Started when the request created it) with `submit_due` today plus 13 days, `review_due` 14 days later and `endorse_due` the later of the Quarter's end and the Cycle's close, flagged as lapsed on the dashboards; after the Cycle has closed it becomes Not Endorsed with no new windows. Both outcomes are audited and notified.
- [ ] The SMO's submission screen has a VA happened form, and the VA POC queue a Confirm VA action (step-up).
- [ ] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
