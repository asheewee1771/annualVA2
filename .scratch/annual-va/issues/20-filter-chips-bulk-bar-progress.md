# 20: Filter chips, select-all, bulk bar and progress bar

**Spec:** [Annual VA spec](../spec.md), stories 36, 37, 38.
**Release:** 1
**What to build:** An SMO works through 100 to 200 Findings with filter chips, tick boxes and select-all, applies one status, one detail and one target date to every selected Finding in one step, and sees how much is left.
**Blocked by:** 19 (List Findings and propose a Finding Status)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 11.6 developer days across stories 36 (4.3*), 37 (4.3*), 38 (3.0); `*` marks the critical path. See `reports/dev-report.md`.

- [ ] `POST /api/submissions/{id}/findings/bulk-proposals` with a list of Finding ids and one status plus its fields creates one batch and one proposal per Finding carrying the batch id, returning the batch id; Findings not in the submission or not editable are refused as a whole.
- [ ] `GET /api/submissions/{id}/readiness` returns total, proposed, pending, carried over, target date missed and disputed counts.
- [ ] Filter chips Needs attention, All, Disputed and Critical/High work server-side (Carried Over and Target Date Missed arrive in Release 2); select-all selects the filtered set; a sticky bulk bar applies the batch and the progress bar shows the readiness counts with `aria-live`.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
