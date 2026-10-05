# 40: Request, freeze and decide an Exemption

**Spec:** [Annual VA spec](../spec.md), stories 43, 44, 45, 46.
**Release:** 1
**What to build:** An SMO requests an Exemption for a System with evidence of a VA within the 3 months before the Quarter or one scheduled within the 3 months after; the submission freezes while a VA POC decides, and a rejection restores it exactly.
**Blocked by:** 34 (Endorse a VA Submission), 36 (In-app notifications and clearing between the two VA POCs)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 15.3 developer days across stories 43 (6.0), 44 (3.0), 45 (2.3), 46 (4.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/systems/{id}/exemptions` (SMO of the System, any state before Endorsed; a Not Started System gets its VA Submission created in the same call) stores basis PRIOR_VA or SCHEDULED_VA, the VA date (validated against the Quarter's 3-month windows), evidence text and optional evidence file, saves the previous state, moves the submission to Exemption Requested and notifies both VA POCs; edits and submit are refused while frozen.
- [ ] `POST /api/exemptions/{id}/approve` and `/reject` (VA POC, step-up) set Exempted (conditional when the basis is a scheduled VA, with the lapse deadline at the Quarter's end plus 3 months) or restore the previous state untouched (a submission created by the request returns to Not Started); a VA POC who is the System's SMO or Cyber Manager, or acted at another step, is refused with `sod-conflict`; both decisions are audited, notify the SMO, and clear the other VA POC's item.
- [ ] `GET /api/dashboards/exemption-queue` lists pending requests; the front end has the request form, the frozen notice and the VA POC queue with Approve and Reject (step-up).
- [ ] Every free-text field introduced here is limited in length and format (IM8 as-1) and carries a classification label next to it (IM8 dp-8).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
