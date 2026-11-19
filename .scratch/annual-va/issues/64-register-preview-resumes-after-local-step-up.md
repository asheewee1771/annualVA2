# 64: The Register change preview resumes after the local step-up, as it does after the single sign-on

**Spec:** [Annual VA spec](../spec.md), story 77 (follow-up raised by the ticket 63 browser test, `artifacts/browser-test/63-dev-local-browsertest.md`).
**Release:** 1
**What to build:** On a developer's machine (`dev-local`), a VA POC applies a Register change preview after the session is older than five minutes. They are sent to `/dev-step-up`, confirm, and come back to an empty upload form. The preview does not reopen and nothing is applied (the audit log has no "applied" entry), so the file must be uploaded again. Under the single sign-on the same round trip reopens the preview, with the withdrawals the VA POC had ticked. Make the local round trip behave the same.
**Blocked by:** 63 (dev-local profile with persona sign-in), done
**Status:** ready-for-agent
**Effort (orchestrator estimate):** not estimated; raised from the ticket 63 browser test of 2026-10-30. Small: front end only.

**Cause (confirmed by reading the code, 2026-11-19):** the step-up arrival is read once, as the page loads, and a load with no `?stepup=` outcome drops the saved route and draft. Under the single sign-on the app is not loaded again until the `?stepup=done` return. Under `dev-local` the server redirects to the app's own `/dev-step-up` page first. That is a full page load with no outcome, so the saved draft (`previewId` and the withdrawals) is cleared before the person confirms. The confirmation then returns with `?stepup=done`, but nothing is left to restore.

- [ ] After Confirm on `/dev-step-up`, the person lands on the screen they left with the Register change preview they were applying open again and the withdrawals they had ticked. Applying it then succeeds, because the session is now fresh for `register-apply`.
- [ ] A load of the local step-up page itself keeps the saved route and draft. Every other rule stays as it is for the single sign-on path: they are dropped when any other page loads with no step-up return, when they are older than a round trip, and when the app finds there is no session.
- [ ] The refused command is never replayed by the front end; the person presses Apply again.
- [ ] "Not now" on `/dev-step-up` leaves nothing restorable behind. Its link is a page load with no step-up return, so the existing rule should already cover it; confirm it in the browser test.
- [ ] No back-end behaviour changes. The back-end gate still passes, and the front-end gate (`npm run check`) passes.
- [ ] The behaviour is checked by a browser test (`/browser-test`) of the dev-local Register apply after a five-minute wait, covering Confirm and "Not now", recorded under `artifacts/browser-test/`. There is no React unit-test seam (ADR 0009), so this browser test is the only check.

## Comments

**2026-11-19, issue verification (HITL 2):** the cause is confirmed from the code, and the browser check is now required in every case, not only when a decision is needed. The criteria are tied to the local step-up page so the single sign-on rules are not loosened. The ticket now uses the glossary term "Register change preview". It is a single slice with no dependency outside done ticket 63, and it does not overlap ticket 65. Approved for execution.
