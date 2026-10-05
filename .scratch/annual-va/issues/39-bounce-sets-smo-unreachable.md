# 39: A rejected address marks the System SMO Unreachable

**Spec:** [Annual VA spec](../spec.md), stories 13.
**Release:** 1
**What to build:** When the mail relay rejects an SMO's address, the System is flagged SMO Unreachable for the VA POC to fix in the Register.
**Blocked by:** 37 (Email through the mail relay with the content rules)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 3.0 developer days across stories 13 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] A rejected-address receipt for an SMO recipient flags every System naming that SMO/Tech Lead as SMO Unreachable with the date, audits it, and shows the flag in the Register table and the VA POC board.
- [ ] A later successful send or a Register upload naming a new SMO clears the flag; the limitation that the relay gives no asynchronous bounce feedback is recorded for the PO (ADR 0014).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
