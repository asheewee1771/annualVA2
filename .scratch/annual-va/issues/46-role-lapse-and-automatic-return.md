# 46: Roles lapse after 90 days and return automatically

**Spec:** [Annual VA spec](../spec.md), stories 80a (lapse), ac-3.
**Release:** 1
**What to build:** A role unused for 90 days lapses and comes back when the Register names the person again, when the Quarter of a System they are named on begins, or when work is handed to them (a Return for Rework, new windows after an Exemption lapse, a transfer). VA POC roles return only through configuration.
**Blocked by:** 10 (Apply a Register preview; SMO and Cyber Manager roles follow the Register), 30 (Return for rework or pass to endorsement), 42 (Conditional Exemption: record the VA, confirm it, or lapse)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.0 developer days across stories 80a (6.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] A daily job lapses active grants whose last use (or grant date) is more than 90 days ago; the person's next request is refused and the lapse is audited.
- [ ] A Register apply naming the person, the start of a Quarter for a System they are named on, a Return for Rework to them, and new windows after a conditional Exemption lapse un-lapse the grant, audited as an automatic return; VA POC grants return only through configuration.
- [ ] Seam 1 tests move the clock 91 days and prove the refusal, then each return trigger and the restored access.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
