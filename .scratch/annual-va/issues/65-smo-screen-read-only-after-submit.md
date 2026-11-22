# 65: The SMO's "Prepare the VA Submission" screen reads as read-only once the VA Submission is submitted

**Spec:** [Annual VA spec](../spec.md), stories 40 and 41 (submit and the Declaration, ticket 25, ADR 0032). This is a follow-up raised by the ticket 63 browser test, `artifacts/browser-test/63-dev-local-browsertest.md`.
**Release:** 1
**What to build:** In the browser test, after "Submit for review" the SMO's "Prepare the VA Submission" screen showed the state "In Review" but still showed its upload and Save controls. The browser test read only the page text and DOM, so it could not tell disabled controls from live ones. ADR 0032 keeps every control that changes the VA Submission visible but disabled once it is submitted: one `<Editable>` fieldset, decided once from the state. Downloads, filters and paging stay live. This ticket keeps that decision. What changes is how it looks: a disabled control must read as read-only, not as busy or live, and the screen must say it is read-only where the person looks first. The server already refuses every change after submit, so this is a misleading screen, not a data problem.
**Blocked by:** none (tickets 21, 22 and 25 are done)
**Status:** closed
**Effort (orchestrator estimate):** not estimated; raised from the ticket 63 browser test of 2026-10-30. Small: front end only.

**What the code already settles (read 2026-11-19):** the state text and whether the screen is editable come from the same reload of the VA Submission, so one cannot update without the other. ADR 0032 lists every changing control inside `<Editable>`: the mode, manual-entry, upload, Replace and scan-date forms, the Findings' tick boxes, status selects and proposal form, select-all and the Bulk-set bar. The likely gap is the look. A disabled button keeps 60 % opacity with a "progress" (busy) cursor, and the only "This VA Submission is read-only now" line sits in the Declaration card at the bottom.

- [x] In the browser, in In Review (and in one more state where the VA Submission is not editable, such as Endorsed), every control ADR 0032 places in `<Editable>` is disabled. Any control found outside it is moved inside it.
- [x] A disabled changing control reads as unavailable, not busy: it has no "progress" cursor and is visibly distinct from a live control. The busy look while a request runs stays as it is for live controls.
- [x] Near the top of the screen, while the VA Submission is not editable, it says in words that the VA Submission is read-only and why (submitted for review, frozen for an Exemption request, or decided). This is on top of the existing line in the Declaration card.
- [x] The screen becomes read-only straight after "Submit for review", without a manual reload, and editable again straight after a return for rework.
- [x] Downloads, filters and paging stay usable in every state.
- [x] The server refusals are unchanged (409 for a change in a state that does not allow it). A Seam 1 test already covers them, and none is weakened.
- [x] The front-end gate (`npm run check`) passes. The dev-local flow SMO → Cyber Manager → VA POC is checked again in the browser (`/browser-test`), with screenshots or DOM evidence that the controls are disabled, and the report goes under `artifacts/browser-test/`.

## Comments

**2026-11-19, issue verification (HITL 2):** the first criterion ("offers no control; text only") contradicted ADR 0032. The reviewer kept ADR 0032, so this ticket now makes the disabled state read as read-only instead of hiding controls. The "find out first" question is partly answered from the code: the state and the editable flag cannot disagree, and every listed control sits in `<Editable>`. What is left is checked in the browser. Trying a control against the server was dropped: a disabled control cannot be pressed, and Seam 1 already covers the 409. The story reference changed from 23 and 39 to 40 and 41 (submit). The ticket now uses the glossary terms "Manually Entered Finding" and "Bulk-set". It is a single slice with no dependency, and it does not overlap ticket 64. Approved for execution.

### Verification (2026-11-22)

**What was built.** Commit `9e005c3`, merged into `main` by `c2916cb`; reports in `fae2432`.
- Every control an `<Editable>` disables now reads as unavailable: muted text on a grey fill, a dashed border and a `not-allowed` cursor, in the light and dark schemes. Tick boxes keep the browser's disabled look. Live and busy controls are unchanged (`frontend/src/styles.css`, `Editable.tsx`).
- A notice near the top of the prepare screen says the VA Submission is read-only and why, with one sentence for each state that cannot be changed. A conditional Exemption says it is still conditional, not decided (`SubmissionPrepareScreen.tsx`).
- When the screen turns read-only while it is open, the focus moves to the notice. A VA Submission that opens read-only does not take the focus.
- `isEditable` in `frontend/src/api/submissions.ts` is the one statement of which states can be changed.
- ADR 0032 is unchanged: controls stay visible but disabled.

**How it was verified.**
- Front-end gate: `npm run check` exits 0, on the ticket's tree and again on merged `main` (`c2916cb`).
- Back-end gate: `./mvnw -B -ntp clean verify` on merged `main` (`c2916cb`): 1,358 tests, 0 failures, 1 skipped, coverage checks met, BUILD SUCCESS. No back-end file changed, and the Seam 1 tests of the 409 refusals are untouched.
- `/code-reviewer`: PASS, no Critical or High finding (`artifacts/code-reviewer/65-smo-screen-read-only-after-submit-compliance.html`). The first round failed on one High (disabled fields still looked live); it was fixed and confirmed fixed with computed styles in both colour schemes. The report says which check saw which round: the react check is from round two, and the semgrep and im8 checks are from round one, so they did not see the final code.
- Browser test, two runs (`artifacts/browser-test/65-smo-read-only-after-submit-browsertest.md`): PASS for criteria 1 to 5 and the flow of criterion 7. DEV-002 went from the SMO to the Cyber Manager (returned once, then passed) to the VA POC (endorsed). In Review, Awaiting Endorsement, Endorsed, Exemption Requested, Exempted (conditional) and Withdrawn each showed the right notice with every `<Editable>` group disabled. The screen turned read-only straight after Submit and Resubmit without a reload, with the focus on the notice, and was editable again after the return for rework.

**Not covered.** The Not Endorsed notice (it needs a VA Cycle to close) and a confirmed, no longer conditional Exemption; a file download and a second page of Findings; a real screen reader, Firefox, Safari and forced-colours mode. In the dark scheme the dashed border is faint; the fill and text colour carry the difference.

**Left open as Low findings, by decision not to widen the ticket:** a button disabled for its own reason in Draft still looks busy; a not-applicable justification is shown twice on the read-only screen; after Submit the page scrolls to the notice.

All seven boxes are ticked.
