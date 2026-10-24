# 41: VA Cycle lifecycle: open, overlap, close

**Spec:** [Annual VA spec](../spec.md), stories 17, 68.
**Release:** 1
**What to build:** A new Cycle opens automatically from the standing Register, the dashboards show a Cycle selector while two Cycles overlap, and a Cycle closes 4 weeks after Q4 ends or earlier when a VA POC closes it, making every unfinished submission Not Endorsed.
**Blocked by:** 34 (Endorse a VA Submission), 06 (VA Cycle and Quarter calendar)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 9.2 developer days across stories 17 (7.9), 68 (1.3); `*` marks the critical path. See `reports/dev-report.md`.
**Data model note (2026-10-05):** ticket 06 computes the calendar and adds no `va_cycle` table (ADR 0015). This ticket adds the table (migration) for `closed_at`, `closed_by` and the early-close override of `closes_on`; the computed `phaseOn` in `VaCycle` is the place to read it. See `docs/agents/reviewer-decisions.md`.
**SMO inbox note (2026-10-24):** the SMO inbox lists a Not Endorsed VA Submission under "Done" (ticket 27, ADR 0034 item 6) until this ticket decides its own heading or label (reviewer decisions, 2026-10-24).

- [ ] A daily job opens the next Cycle when its Q1 starts; the new Cycle takes its Systems and Quarters from the standing Register; while two Cycles overlap a Register apply changes only the newer Cycle; `GET /api/cycles` marks which Cycles are open and the shell's Cycle selector filters every dashboard call.
- [ ] `POST /api/cycles/{year}/close` (VA POC, step-up, with a confirm dialog in the UI) and the daily auto-close after `closes_on` set every non-final submission in live Quarters (including Exemption Requested) to Not Endorsed, create a Not Endorsed row for each Not Started System, record the close, audit each change and notify the SMOs.
- [ ] After the close no transition is accepted (409) except, for Q4 conditional Exemptions, recording that the VA happened, confirming it, or lapsing it to Not Endorsed (next ticket).
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
