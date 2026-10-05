# 34: Endorse a VA Submission

**Spec:** [Annual VA spec](../spec.md), stories 65, 67, 70.
**Release:** 1
**What to build:** A VA POC endorses an Awaiting Endorsement submission with a fresh sign-in (Endorsed Late after the Quarter's last day); a VA POC who acted at another step on it is blocked so the other VA POC endorses.
**Blocked by:** 30 (Return for rework or pass to endorsement), 09 (Step-up: a fresh sign-in before sensitive actions)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 8.7 developer days across stories 65 (4.0), 67 (2.0), 70 (2.7); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/submissions/{id}/endorse` (VA POC, step-up, Awaiting Endorsement) sets Endorsed, marks Endorsed Late when today's business date is after `endorse_due`, records the deciding step, audits and publishes an FYI event.
- [ ] A VA POC who acted at the submitting or reviewing step on that submission, or who is the System's SMO or Cyber Manager, is refused with 403 `sod-conflict`; the other VA POC can then endorse, and a test proves both halves.
- [ ] The endorsement view shows the read-only Findings, Endorse (step-up) and the Endorsed Late marker.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
