# 62: Per-row disposal checks on every append-only history table

**Spec:** [Annual VA spec](../spec.md), story 87 (follow-up raised by ticket 49).
**Release:** 1
**What to build:** While a disposal list is `APPROVED`, a transaction that names it in `annualva.disposal_list` can today delete any row of the append-only tables, not only the rows the list covers; only `audit_entry` is checked row by row (ADR 0050 item 7 and consequences). Make every append-only history table's delete trigger check, for each row, that the row is on the `APPROVED` list being executed, so the executor, or a bug in it, can remove only what a VA POC approved.
**Blocked by:** 49 (Retention: disposal list, approval and hard delete)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** not estimated; raised by the ticket 49 decision of 2026-10-25 (`docs/agents/reviewer-decisions.md`, "Ticket 49: retention dates, and per-row disposal checks as a follow-up").

- [ ] Every append-only history table that the disposal path opens (`finding_status_proposal`, `bulk_batch`, and the tables sharing `declaration_append_only`: the Declarations, reviews, batch reviews, returns and return items) has a per-row delete trigger that refuses a row not covered by the running `APPROVED` list (its VA Submission, Cycle or System is one of the list's items); `audit_entry` keeps its cutoff check.
- [ ] A transaction naming an `APPROVED` list cannot delete a row of another VA Submission, Cycle or System from any of those tables; a Seam 1 test on the application's own connection proves the refusal for each table.
- [ ] The executor's approved disposal still deletes everything the list names, in foreign-key order, and its tombstone and counts are unchanged; the existing disposal tests pass.
- [ ] `UPDATE` and `TRUNCATE` stay refused always, a DRAFT, unknown or malformed list still opens nothing, and `declaration_text` is never opened.
- [ ] ADR 0050's consequence "accepted for now" is replaced by the per-row rule, and the reviewer decision for ticket 49 is updated.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API or the application's own connection on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
