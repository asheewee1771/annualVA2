# 32: Review engine 3: re-review only what changed

**Spec:** [Annual VA spec](../spec.md), stories 56.
**Release:** 1
**What to build:** When a submission comes back, the Cyber Manager re-reviews only the Findings whose proposals changed; everything they confirmed before stays confirmed.
**Blocked by:** 30 (Return for rework or pass to endorsement)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 6.0 developer days across stories 56 (6.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] A resubmitted submission keeps CONFIRMED on Findings whose current proposal is unchanged and shows PENDING only on changed ones; `GET /api/submissions/{id}/findings?changedSinceLastReview=true` lists exactly those.
- [ ] Passing to endorsement after a resubmission needs only the changed Findings confirmed; the review screen offers a Changed since last review filter.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
