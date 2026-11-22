# 62: Per-row disposal checks on every append-only history table

**Spec:** [Annual VA spec](../spec.md), story 87 (follow-up raised by ticket 49).
**Release:** 1
**What to build:** While a disposal list is `APPROVED`, a transaction that names it in `annualva.disposal_list` can today delete any row of the append-only tables, not only the rows the list covers; only `audit_entry` is checked row by row (ADR 0050 item 7 and consequences). Make every append-only history table's delete trigger check, for each row, that the row is on the `APPROVED` list being executed, so the executor, or a bug in it, can remove only what a VA POC approved.
**Blocked by:** 49 (Retention: disposal list, approval and hard delete)
**Status:** closed
**Effort (orchestrator estimate):** not estimated; raised by the ticket 49 decision of 2026-10-25 (`docs/agents/reviewer-decisions.md`, "Ticket 49: retention dates, and per-row disposal checks as a follow-up").

- [x] Every append-only history table that the disposal path opens (`finding_status_proposal`, `bulk_batch`, and the tables sharing `declaration_append_only`: the Declarations, reviews, batch reviews, returns and return items) has a per-row delete trigger that refuses a row not covered by the running `APPROVED` list (its VA Submission, Cycle or System is one of the list's items); `audit_entry` keeps its cutoff check.
- [x] A transaction naming an `APPROVED` list cannot delete a row of another VA Submission, Cycle or System from any of those tables; a Seam 1 test on the application's own connection proves the refusal for each table.
- [x] The executor's approved disposal still deletes everything the list names, in foreign-key order, and its tombstone and counts are unchanged; the existing disposal tests pass.
- [x] `UPDATE` and `TRUNCATE` stay refused always, a DRAFT, unknown or malformed list still opens nothing, and `declaration_text` is never opened.
- [x] ADR 0050's consequence "accepted for now" is replaced by the per-row rule, and the reviewer decision for ticket 49 is updated.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API or the application's own connection on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Verification (2026-11-22)

**What was built.** Migration V44 adds a per-row `BEFORE DELETE` trigger to each of the eight append-only history tables (`finding_status_proposal`, `bulk_batch`, `declaration`, `finding_review`, `report_review`, `batch_review`, `submission_return`, `submission_return_item`) through one function, `history_row_disposal_listed()`. A row is deleted only when its VA Submission is a `SUBMISSION` item of the `APPROVED` list the transaction names; a table the function does not know is refused. The V36 statement-level triggers are unchanged and fire first. An index on `disposal_item (list_id, kind, target_id)` keeps the per-row lookup cheap. Commits `dfa0dc7` (migration, Javadoc, tests), `8bdd426` (ADR 0050, decision log, README) and `a9b9379` (report), merged into `main` by `bde37c1`.

**One reading of criteria 1 and 2:** every one of the eight tables reaches exactly one VA Submission by foreign key, so the check is by VA Submission only. A row of another Cycle or System is necessarily a row of another VA Submission. Both review rounds accepted this.

**How it was verified.**
- Seam 1 (`DisposalGuardTest`, 5 tests, on the application's own connection and a real PostgreSQL): for each table, an unfiltered delete in an `APPROVED` list's transaction is refused with the per-table message and removes nothing; the executor then removes exactly the listed VA Submission's history and keeps the other's (proved from per-table row totals); a DRAFT list still opens nothing; the audit entry from the cutoff on is kept; a table the check does not know is refused. A schema-derived test fails if a table with an `_append_only` trigger lacks the row trigger. The implementer broke three hops of the function one at a time and the test failed each time.
- Existing disposal tests pass, with their assertions unchanged.
- Back-end gate on the final tree: `./mvnw -B -ntp clean verify`, 1,361 tests, 0 failures, 1 skipped, coverage checks met, BUILD SUCCESS. `main` after the merge has the same back end as that tree. Front-end gate: not applicable, no front-end file changed.
- `/code-reviewer`: PASS, no Critical or High (`artifacts/code-reviewer/62-per-row-disposal-checks-on-history-tables-compliance.html`). Two rounds and two correction passes; the report says which checks saw the final tree.

**Decided by the user on 2026-11-22:** the check still trusts an approved list's items as stored and a Finding's link to its VA Submission. Both are tracked by ticket 66 (follow-up), not accepted as permanent.

**Left open, not blocking:** a shared test helper (`enteredByHandAndNotApplicable`) sits in `DisposalSeam1`, where three older copies of the same lines in other test seams cannot reach it; moving it is a small `test:` follow-up. No `lock_timeout` in the migration and the per-row cost was not measured on a large list.

All six boxes are ticked.
