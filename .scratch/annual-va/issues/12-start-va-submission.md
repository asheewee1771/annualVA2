# 12: Start a VA Submission

**Spec:** [Annual VA spec](../spec.md), stories 19.
**Release:** 1
**What to build:** An SMO/Tech Lead starts the VA Submission for one of their Systems, which creates the Draft with its windows taken from the Quarter and records them as the submitter for segregation of duties.
**Blocked by:** 11 (SMO inbox: my Systems and what to do by when)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.3 developer days across stories 19 (6.3*); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/submissions` with a System id (SMO of that System) creates one VA Submission per System and Cycle in state Draft with `submit_due`, `review_due` and `endorse_due` taken from the Quarter, and returns it; a second start answers 409 `invalid-transition`. (Windows from the day a System was added arrive with the Release 2 mid-cycle changes.)
- [ ] Starting is allowed before the Quarter begins; a Cyber Manager, a VA POC or another SMO is refused with 403 or 404 as the ownership rules dictate.
- [ ] The caller is recorded as having acted at the submitting step on this VA Submission (the segregation-of-duties record); the start is audited.
- [ ] `GET /api/submissions/{id}` returns the submission with state, flags, due dates and who acted at which step to anyone allowed to see the System.
- [ ] The SMO inbox's Start button opens the new submission's prepare screen (three empty Scan Report cards, Declaration disabled).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
