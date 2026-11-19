# 65: The SMO's "Prepare the VA Submission" screen reads as read-only once the VA Submission is submitted

**Spec:** [Annual VA spec](../spec.md), stories 40 and 41 (submit and the Declaration, ticket 25, ADR 0032). This is a follow-up raised by the ticket 63 browser test, `artifacts/browser-test/63-dev-local-browsertest.md`.
**Release:** 1
**What to build:** In the browser test, after "Submit for review" the SMO's "Prepare the VA Submission" screen showed the state "In Review" but still showed its upload and Save controls. The browser test read only the page text and DOM, so it could not tell disabled controls from live ones. ADR 0032 keeps every control that changes the VA Submission visible but disabled once it is submitted: one `<Editable>` fieldset, decided once from the state. Downloads, filters and paging stay live. This ticket keeps that decision. What changes is how it looks: a disabled control must read as read-only, not as busy or live, and the screen must say it is read-only where the person looks first. The server already refuses every change after submit, so this is a misleading screen, not a data problem.
**Blocked by:** none (tickets 21, 22 and 25 are done)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** not estimated; raised from the ticket 63 browser test of 2026-10-30. Small: front end only.

**What the code already settles (read 2026-11-19):** the state text and whether the screen is editable come from the same reload of the VA Submission, so one cannot update without the other. ADR 0032 lists every changing control inside `<Editable>`: the mode, manual-entry, upload, Replace and scan-date forms, the Findings' tick boxes, status selects and proposal form, select-all and the Bulk-set bar. The likely gap is the look. A disabled button keeps 60 % opacity with a "progress" (busy) cursor, and the only "This VA Submission is read-only now" line sits in the Declaration card at the bottom.

- [ ] In the browser, in In Review (and in one more state where the VA Submission is not editable, such as Endorsed), every control ADR 0032 places in `<Editable>` is disabled. Any control found outside it is moved inside it.
- [ ] A disabled changing control reads as unavailable, not busy: it has no "progress" cursor and is visibly distinct from a live control. The busy look while a request runs stays as it is for live controls.
- [ ] Near the top of the screen, while the VA Submission is not editable, it says in words that the VA Submission is read-only and why (submitted for review, frozen for an Exemption request, or decided). This is on top of the existing line in the Declaration card.
- [ ] The screen becomes read-only straight after "Submit for review", without a manual reload, and editable again straight after a return for rework.
- [ ] Downloads, filters and paging stay usable in every state.
- [ ] The server refusals are unchanged (409 for a change in a state that does not allow it). A Seam 1 test already covers them, and none is weakened.
- [ ] The front-end gate (`npm run check`) passes. The dev-local flow SMO → Cyber Manager → VA POC is checked again in the browser (`/browser-test`), with screenshots or DOM evidence that the controls are disabled, and the report goes under `artifacts/browser-test/`.

## Comments

**2026-11-19, issue verification (HITL 2):** the first criterion ("offers no control; text only") contradicted ADR 0032. The reviewer kept ADR 0032, so this ticket now makes the disabled state read as read-only instead of hiding controls. The "find out first" question is partly answered from the code: the state and the editable flag cannot disagree, and every listed control sits in `<Editable>`. What is left is checked in the browser. Trying a control against the server was dropped: a disabled control cannot be pressed, and Seam 1 already covers the 409. The story reference changed from 23 and 39 to 40 and 41 (submit). The ticket now uses the glossary terms "Manually Entered Finding" and "Bulk-set". It is a single slice with no dependency, and it does not overlap ticket 64. Approved for execution.
