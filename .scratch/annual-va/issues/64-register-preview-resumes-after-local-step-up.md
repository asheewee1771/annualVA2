# 64: The Register preview resumes after the local step-up, as it does after the single sign-on

**Spec:** [Annual VA spec](../spec.md), story 77 (follow-up raised by the ticket 63 browser test, `artifacts/browser-test/63-dev-local-browsertest.md`).
**Release:** 1
**What to build:** On a developer's machine (`dev-local`), a VA POC who applies a System Register preview after the session is older than five minutes is sent to `/dev-step-up`, confirms, and comes back to an empty upload form: the preview does not reopen and nothing is applied (the audit log has no "applied" entry), so the file must be uploaded again. Under the single sign-on the same round trip is meant to reopen the preview (`frontend/src/api/stepUp.ts`: "keeps where the person was and any form"). Make the local round trip behave the same.
**Blocked by:** 63 (dev-local profile with persona sign-in), done
**Status:** needs-triage
**Effort (orchestrator estimate):** not estimated; raised from the ticket 63 browser test of 2026-10-30.

**Likely cause (not yet confirmed):** `takeStepUpArrival()` drops the saved route and draft "when a page loads without a step-up return". Under the single sign-on the browser leaves the app for the identity provider and the app is not loaded again until the `?stepup=done` return. Under `dev-local` the browser loads the app's own `/dev-step-up` page first, which has no `?stepup=` outcome, so the saved draft (`{ previewId, withdraw }`) is cleared before the person confirms. `DevStepUpScreen` then sends them back with `?stepup=done`, but there is nothing left to restore.

- [ ] After Confirm on `/dev-step-up`, the person lands on the screen they left and the Register preview they were applying is open again, with the withdrawals they had ticked; applying it then succeeds (a fresh session for `register-apply`).
- [ ] The saved route and draft are still dropped when a page loads with no step-up return anywhere other than the local step-up page, when they are older than a round trip, and when the app finds there is no session (the rules of `stepUp.ts` stay as they are for the single sign-on path).
- [ ] The refused command is never replayed by the front end; the person presses Apply again.
- [ ] "Not now" on `/dev-step-up` leaves nothing restorable behind.
- [ ] No back-end behaviour changes; the back-end gate still passes, and the front-end gate (`npm run check`) passes. Business rules are not tested in the browser: if the fix needs a decision about what survives, it is checked by a browser test (`/browser-test`) of the dev-local Register apply after a five-minute wait, recorded under `artifacts/browser-test/`.

## Comments
