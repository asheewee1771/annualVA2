# 60: Delete a report file or a Manually Entered Finding while the VA Submission is editable

**Spec:** [Annual VA spec](../spec.md), stories 23 and 39 (follow-up raised by ticket 22).
**Release:** 1
**What to build:** An SMO can undo a mistake on a Scan Report while the VA Submission is in Draft or Returned for Rework: delete a report file that was uploaded by mistake, or a Manually Entered Finding that was entered by mistake. Once a Scan Report holds nothing, its mode can change again (ADR 0030).
**Blocked by:** 21 (Replace a Scan Report file without losing work), 22 (Mark a Scan Report not applicable, or enter Findings by hand)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** not estimated; raised by the ticket 22 decision of 2026-10-18 (`docs/agents/reviewer-decisions.md`, "A Scan Report's mode is fixed once it holds anything").

- [ ] An SMO of the System can delete a report file (any status other than superseded) or a Manually Entered Finding of a Scan Report while the VA Submission is in Draft or Returned for Rework; any other state is a 409, audited; a stranger or a missing record gets the same 404.
- [ ] Deleting a report file removes its occurrences and re-rates the Findings it touched (ADR 0029); a Finding left with no places is removed if it never had a proposal, and otherwise stays and follows the rescan rule (ADR 0029 item 5): it gets a Remediated proposal saying no file of the Scan Report reports it any more, unless it is already Remediated. Proposals and their history are never deleted.
- [ ] Deletion is audited with ids and fixed codes only; the retention rules of ticket 49 cover what a deleted record leaves behind.
- [ ] When the Scan Report holds no non-rejected file and no Finding after a delete, its mode can be changed again.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

**2026-11-22, issue verification (HITL 2):** the open question is decided by the user: a Finding that deleting a file leaves with no places is removed only if it never had a proposal; one with a proposal stays and follows the rescan rule of ADR 0029. The ticket now uses the glossary term "Manually Entered Finding". It is one vertical slice (endpoints, the screen's delete controls, Seam 1 tests), can be demonstrated alone, and its blockers (21, 22) are built. Tickets 62 and 66 add delete triggers to the append-only history tables: this ticket deletes no proposal or review row, so they do not block it, but its deletes of a Finding must not reach those tables. Approved for execution.
