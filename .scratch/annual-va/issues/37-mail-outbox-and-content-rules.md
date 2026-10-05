# 37: Email through the mail relay with the content rules

**Spec:** [Annual VA spec](../spec.md), stories 91, 94, 88 to 90 (plumbing).
**Release:** 1
**What to build:** Required notifications also go out by email through the organisation's mail relay. A message carries only the System, state, action, due date and a link, so a forwarded email leaks nothing.
**Blocked by:** 36 (In-app notifications and clearing between the two VA POCs)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 14.0 developer days across stories 91 (1.0), 94 (2.0), 88 (10.0), 90 (1.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] REQUIRED notifications are queued for email; a dispatcher sends them through the mail relay boundary (recipient, template key and allow-listed values), retries failures with backoff up to five attempts, and records the relay's message id.
- [ ] The relay adapter maps logical template keys to configured template ids and refuses any value outside the allow-list before calling out; no recipient address appears in logs.
- [ ] Return for Rework, Disputed and Register-change messages (moved, transferred, withdrawn) reach the SMO; a sentinel Finding never appears in any captured mail; the Seam 1 harness gains a capturing mail relay with a reject-address knob.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
