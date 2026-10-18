# 60: Delete a report file or a manually entered Finding while the VA Submission is editable

**Spec:** [Annual VA spec](../spec.md), stories 23 and 39 (follow-up raised by ticket 22).
**Release:** 1
**What to build:** An SMO can undo a mistake on a Scan Report while the VA Submission is in Draft or Returned for Rework: delete a report file that was uploaded by mistake, or a Finding that was entered by hand by mistake. Once a Scan Report holds nothing, its mode can change again (ADR 0030).
**Blocked by:** 21 (Replace a Scan Report file without losing work), 22 (Mark a Scan Report not applicable, or enter Findings by hand)
**Status:** needs-triage
**Effort (orchestrator estimate):** not estimated; raised by the ticket 22 decision of 2026-10-18 (`docs/agents/reviewer-decisions.md`, "A Scan Report's mode is fixed once it holds anything").

- [ ] An SMO of the System can delete a report file (any status other than superseded) or a manually entered Finding of a Scan Report while the VA Submission is in Draft or Returned for Rework; any other state is a 409, audited; a stranger or a missing record gets the same 404.
- [ ] Deleting a report file removes its occurrences and re-rates the Findings it touched (ADR 0029); a Finding left with no places follows the same rule as a rescan, or is removed if it never had a proposal (to be decided with the human when this ticket is picked up); proposals and their history are never deleted.
- [ ] Deletion is audited with ids and fixed codes only; the retention rules of ticket 49 cover what a deleted record leaves behind.
- [ ] When the Scan Report holds no non-rejected file and no Finding after a delete, its mode can be changed again.
- [ ] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments
