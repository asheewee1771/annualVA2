# 65: The SMO's "Prepare the VA Submission" screen is read-only once the VA Submission is submitted

**Spec:** [Annual VA spec](../spec.md), stories 23 and 39 (follow-up raised by the ticket 63 browser test, `artifacts/browser-test/63-dev-local-browsertest.md`).
**Release:** 1
**What to build:** In the browser test, after "Submit for review" the SMO's "Prepare the VA Submission" screen showed the state "In Review" but still showed its upload and Save controls. The code says the screen is read-only once submitted (`isEditable` is true only for Draft and Returned for Rework, and `EditableContext` is meant to hide or disable the controls), and the server refuses changes in any other state, so this is at worst a misleading screen, not a data problem. Find out what the screen really shows after submit and make it match: no control that cannot work.
**Blocked by:** none (tickets 21, 22 and 25 are done)
**Status:** needs-triage
**Effort (orchestrator estimate):** not estimated; raised from the ticket 63 browser test of 2026-10-30.

**To find out first:** whether the controls were still showing because the screen had not reloaded the VA Submission after the submit (the state text updated, `editable` did not), because some controls do not read `EditableContext` (the upload, the Save on a Finding, the manual Finding form, the Scan Report mode form), or because they were there but disabled and only looked live. The browser test did not try them, so also try one and see which refusal the server gives (it should be a 409, audited).

- [ ] In every state except Draft and Returned for Rework (In Review, Awaiting Endorsement, Endorsed, Exemption Requested, Exempted, Not Endorsed), the SMO's prepare screen offers no upload, replace, mode change, Finding edit, bulk change, manual Finding or Declaration control; what was submitted is shown as text only.
- [ ] The screen is read-only straight after "Submit for review", without a manual reload, and again straight after a return for rework makes it editable.
- [ ] A control that stays visible but cannot act is disabled and says why in words, not left live.
- [ ] The server refusals are unchanged (409 for a change in a state that does not allow it); a Seam 1 test already covers them, and none is weakened.
- [ ] The front-end gate (`npm run check`) passes, and the dev-local flow SMO → Cyber Manager → VA POC is checked again in the browser (`/browser-test`), with the report under `artifacts/browser-test/`.

## Comments
