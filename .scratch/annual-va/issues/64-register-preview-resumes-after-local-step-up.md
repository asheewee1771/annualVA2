# 64: The Register change preview resumes after the local step-up, as it does after the single sign-on

**Spec:** [Annual VA spec](../spec.md), story 77 (follow-up raised by the ticket 63 browser test, `artifacts/browser-test/63-dev-local-browsertest.md`).
**Release:** 1
**What to build:** On a developer's machine (`dev-local`), a VA POC applies a Register change preview after the session is older than five minutes. They are sent to `/dev-step-up`, confirm, and come back to an empty upload form. The preview does not reopen and nothing is applied (the audit log has no "applied" entry), so the file must be uploaded again. Under the single sign-on the same round trip reopens the preview, with the withdrawals the VA POC had ticked. Make the local round trip behave the same.
**Blocked by:** 63 (dev-local profile with persona sign-in), done
**Status:** closed
**Effort (orchestrator estimate):** not estimated; raised from the ticket 63 browser test of 2026-10-30. Small: front end only.

**Cause (confirmed by reading the code, 2026-11-19):** the step-up arrival is read once, as the page loads, and a load with no `?stepup=` outcome drops the saved route and draft. Under the single sign-on the app is not loaded again until the `?stepup=done` return. Under `dev-local` the server redirects to the app's own `/dev-step-up` page first. That is a full page load with no outcome, so the saved draft (`previewId` and the withdrawals) is cleared before the person confirms. The confirmation then returns with `?stepup=done`, but nothing is left to restore.

- [x] After Confirm on `/dev-step-up`, the person lands on the screen they left with the Register change preview they were applying open again and the withdrawals they had ticked. Applying it then succeeds, because the session is now fresh for `register-apply`.
- [x] A load of the local step-up page itself keeps the saved route and draft. Every other rule stays as it is for the single sign-on path: they are dropped when any other page loads with no step-up return, when they are older than a round trip, and when the app finds there is no session.
- [x] The refused command is never replayed by the front end; the person presses Apply again.
- [x] "Not now" on `/dev-step-up` leaves nothing restorable behind. Its link is a page load with no step-up return, so the existing rule should already cover it; confirm it in the browser test.
- [x] No back-end behaviour changes. The back-end gate still passes, and the front-end gate (`npm run check`) passes.
- [x] The behaviour is checked by a browser test (`/browser-test`) of the dev-local Register apply after a five-minute wait, covering Confirm and "Not now", recorded under `artifacts/browser-test/`. There is no React unit-test seam (ADR 0009), so this browser test is the only check.

## Comments

**2026-11-19, issue verification (HITL 2):** the cause is confirmed from the code, and the browser check is now required in every case, not only when a decision is needed. The criteria are tied to the local step-up page so the single sign-on rules are not loosened. The ticket now uses the glossary term "Register change preview". It is a single slice with no dependency outside done ticket 63, and it does not overlap ticket 65. Approved for execution.

### Verification (2026-11-22)

**What was fixed.** `frontend/src/api/stepUp.ts` now reads the step-up outcome, the saved route and the saved draft together, once, as the page loads. A load of the local step-up page (`/dev-step-up`) is the middle of a round trip and keeps what was saved; every other load with no step-up return drops it. `useStepUp.ts`, `devLocal.ts` and `Bodies.tsx` changed with it. Commit `f4621d4`; reports in `1846f21`.

**One intended behaviour change, on the single sign-on path too:** a saved draft is restored only on a load that carries the server's `?stepup=` outcome. Before, a form could take a saved draft on a later load with no return, for up to 15 minutes.

**How it was verified.**
- Front-end gate: `npm run check` exits 0, on the ticket's tree and again on merged `main` (`c2916cb`).
- Back-end gate: `./mvnw -B -ntp clean verify` on merged `main` (`c2916cb`): 1,358 tests, 0 failures, 1 skipped, coverage checks met, BUILD SUCCESS. No back-end file changed.
- `/code-reviewer`: PASS, no Critical or High finding (`artifacts/code-reviewer/64-register-preview-resumes-after-local-step-up-compliance.html`). Three Mediums of the first round (a hidden read-once cache, a dev-local special case in the shared module, the `api` layer naming an `app` route) were fixed by a restructuring and confirmed fixed by the second round. The im8 check is carried over from the first round and did not see the restructured code.
- Browser test on the final tree, 2026-11-22 (`artifacts/browser-test/64-register-preview-resumes-browsertest.md`): PASS for criteria 1 to 4. A Register file without DEV-004 gave a preview with one VA Submission to withdraw. After the five-minute wait, "Not now" left nothing restorable. Confirm reopened the same preview with the withdrawal still ticked, and Apply then succeeded. The audit log shows the single `register-applied` only after the button was pressed again.

**Not covered.** In the browser: an entry older than 15 minutes, the drop when there is no session, HTTPS (the run was over plain HTTP), a production build, and the real single sign-on round trip. Those rules are unchanged in the code and were read by the reviews, not run.

**Raised for triage, not part of this ticket:** a preview that only retires a System says "Nothing has changed."; the step-up dialog names the organisation single sign-on under `dev-local`; a System added back after a retire keeps its Withdrawn VA Submission with no way to start a new one.

All six boxes are ticked.
