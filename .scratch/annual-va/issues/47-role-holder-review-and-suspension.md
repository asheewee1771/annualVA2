# 47: Role-holder review, suspension and identity-provider disablement

**Spec:** [Annual VA spec](../spec.md), stories 80a (removal), ac-3, ac-4.
**Release:** 1
**What to build:** The VA POCs review every role holder (SMO/Tech Leads, Cyber Managers, VA POCs) against the Register baseline at each Register upload and at least quarterly, see the automatic returns, and may suspend a person's role for a System with a fresh sign-in; a person disabled at the identity provider loses access at once.
**Blocked by:** 46 (Roles lapse after 90 days and return automatically)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.0 developer days across stories 80a (6.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `GET /api/role-holders` lists every holder against the Register baseline with the automatic returns since the last review, and flags when no review has been recorded in 3 months; `POST /api/role-holders/reviews` (VA POC) records a completed review, audited.
- [ ] `POST /api/role-holders/{grantId}/suspend` (VA POC, step-up) suspends a role for a System until a Register upload names someone; a VA POC cannot suspend or restore their own grant (403); suspensions are audited.
- [ ] A person reported disabled at the identity provider (the sign-in callback or a session check sees the disabled status) has every grant suspended immediately and their session ended.
- [ ] The Role-holder review screen lists holders, shows the review-due flag and offers Review completed and Suspend (step-up).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
