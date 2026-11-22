# 66: An approved disposal list's items and a Finding's link to its VA Submission cannot change

**Spec:** [Annual VA spec](../spec.md), story 87 (follow-up raised by the ticket 62 review).
**Release:** 1
**What to build:** Ticket 62 made every append-only history table check, row by row, that a deleted row belongs to a VA Submission on the `APPROVED` disposal list the transaction names. That check trusts two things the database does not freeze (ADR 0050 item 7). First, the list's items as stored: `disposal_item` has no guard, so a transaction could add a VA Submission to an `APPROVED` list and then delete its history. Second, a Finding's link to its VA Submission: `finding.submission_id` can still be updated, and it is the hop for two of the history tables. No application code does either today. Make the database refuse both, so the per-row check rests only on what a VA POC approved.
**Blocked by:** 62 (Per-row disposal checks on every append-only history table)
**Status:** closed
**Effort (orchestrator estimate):** not estimated; raised by the ticket 62 decision of 2026-11-22. Small: one migration and its Seam 1 tests.

- [x] Once a disposal list has left `DRAFT`, the database refuses to add an item to it, change one of its items or remove one, whatever the transaction names in `annualva.disposal_list`. The planner's insert of the items of a new `DRAFT` list still works, and so does anything the executor itself does to the list and its items when it executes (the ADR says what that is).
- [x] The database refuses an update that changes the VA Submission a Finding belongs to. Every other update the application makes to a Finding still works.
- [x] A Seam 1 test on the application's own connection proves both refusals: an item added to an `APPROVED` list is refused, and the history of the VA Submission it named is still there afterwards; a Finding moved to another VA Submission is refused.
- [x] The approved disposal still deletes everything the list names, with its tombstone and counts unchanged; the existing disposal tests pass unmodified in what they assert.
- [x] ADR 0050 item 7 and the ticket 49 entry in `docs/agents/reviewer-decisions.md` say that the two residual trusts are closed, and name exactly what the per-row check still trusts, if anything (for example a superuser or the table owner, which ADR 0050 already puts outside the threat model).
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API or the application's own connection on a real PostgreSQL, with only the boundary adapters faked. The back-end gate passes. The front-end gate applies only if the front end changes.

## Comments

**2026-11-22, raised and approved:** the ticket 62 code review (finding S1) asked for the two residual trusts to be decided by a person, not only written down. The user chose a follow-up ticket over accepting the residual or widening ticket 62, and asked for it to be built with the other remaining tickets, so it is `ready-for-agent`. It is one vertical slice (a migration, its Seam 1 tests and the ADR), can be demonstrated alone once ticket 62 has landed, and overlaps no other open ticket.

### Verification (2026-11-22)

**What was built.** Migration V45 makes a disposal list's items insert-only, and only into a DRAFT list: an update, delete or truncate of an item is always refused, whatever the transaction names in `annualva.disposal_list`. The list row is read `FOR SHARE`, so an insert that races an approval waits and then sees `APPROVED`. A Finding's `submission_id` cannot change. The planner's insert for a new DRAFT list still works; the executor writes no item. Commits `97ab32c` (migration and tests), `f375fc1` (ADR 0050 item 7, decision log, README), `ac65fbc` (report), merged by `0a1f576`.

**Decided by the user on 2026-11-22.** The disposal list's own row stays trusted: a transaction could move an APPROVED list back to DRAFT by hand-written SQL. It is accepted as a residual within ADR 0014's threat model (application bugs, not hand-written SQL), because no application statement does it; no follow-up ticket.

**How it was verified.**
- Seam 1 (`DisposalGuardTest`, 8 tests): an item cannot be updated, deleted or truncated on a DRAFT or an approved list, nor inserted into an approved list; an insert naming another VA Submission is refused and that VA Submission's history stays; the list executes with exactly its approved items; a Finding cannot move to another VA Submission. Before V45 existed, the item test failed only on a foreign key after the insert had gone through, so the gap was real. Mutants of the trigger were caught.
- Back-end gate on `main` after the merge (`0a1f576`): `./mvnw -B -ntp clean verify`, 1,364 tests, 0 failures, 1 skipped, coverage checks met, BUILD SUCCESS. Front-end gate: not applicable.
- `/code-reviewer`: no Critical, High or Medium open (`artifacts/code-reviewer/66-freeze-approved-disposal-items-and-finding-link-compliance.html`); thermo-nuclear's two Mediums were fixed in a correction pass and confirmed.

**Not covered:** a concurrent approval and insert is reasoned from the `FOR SHARE` lock, not raced in a test; the migration sets no `lock_timeout`.

All six boxes are ticked.
