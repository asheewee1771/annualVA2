# 57: Onboard the notification templates to MCNS

**Spec:** [Annual VA spec](../spec.md), stories 88 to 94 (SIT).
**Release:** 1
**What to build:** Every message the app sends has an onboarded MCNS template with the allowed keys, so emails can go out in SIT and beyond.
**Blocked by:** 37 (Email through the mail relay with the content rules)
**Status:** ready-for-human
**Effort (orchestrator estimate):** about 12.0 developer days across stories 88 (10.0), 94 (2.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] Templates for: reminder due soon, reminder due, overdue weekly, the Cyber Manager and VA POC copy variants, returned for rework, disputed, register change, exemption decision, endorsed FYI and the weekly digest (Release 2), each accepting only System, state, action, due date, link and change kind.
- [ ] Template ids and the sender id are recorded in `application-sit.yml`; the lack of delivery and bounce feedback is raised with the MCNS owners and the answer recorded on the SMO Unreachable ticket.

## Comments
