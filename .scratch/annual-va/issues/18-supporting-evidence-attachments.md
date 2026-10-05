# 18: Attach PDF or HTML reports as supporting evidence

**Spec:** [Annual VA spec](../spec.md), stories 22.
**Release:** 1
**What to build:** An SMO attaches PDF or HTML reports to a Scan Report for people to read; they go through the same pipeline but are never parsed. The same path later carries a Risk Accepted approval memo.
**Blocked by:** 13 (Scan Report upload pipeline: quarantine, scan, check, store)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 2.3 developer days across stories 22 (2.3); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] Files uploaded with kind EVIDENCE accept `%PDF-` and HTML content up to their limits, run quarantine, scan and type checks, skip schema and parse, and become READY; anything else is rejected.
- [ ] Evidence is downloadable only when clean and only by someone allowed to see the System, as an attachment, audited; HTML is never rendered by the app.
- [ ] The Scan Report cards list evidence separately from machine-readable files.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
