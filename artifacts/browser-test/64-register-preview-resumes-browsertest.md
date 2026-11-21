# Browser Acceptance Test Report

**Date:** 2026-11-22 01:21 to 01:30 (Singapore time). This run replaces the run of 2026-11-21, which tested the code before the step-up arrival read was restructured.
**Issue:** ticket 64, `.scratch/annual-va/issues/64-register-preview-resumes-after-local-step-up.md`
**Base URL:** `http://localhost:5173` (Vite dev server from the main checkout, proxying the back end on `https://localhost:8443`)
**Run Command:** `docker compose up -d postgres`; back end `backend\mvnw.cmd -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev-local`; front end `npm run dev --prefix frontend -- --config vite.http.local.config.ts`
**Scope:** all criteria a browser can show (1 to 4). Tree under test: `HEAD` ba45b05 plus the uncommitted ticket 64 change in four files (`frontend/src/api/stepUp.ts`, `frontend/src/app/useStepUp.ts`, `frontend/src/app/devLocal.ts`, `frontend/src/app/Bodies.tsx`). The served `stepUp.ts` contained `readLanding` and `midRoundTrip`.
**Browser:** the built-in browser pane of the Claude desktop app. agent-browser is not installed. The pane refuses Vite's self-signed certificate, so the run used a throwaway plain-HTTP Vite config (deleted afterwards).

## Results Summary

| Criterion | Passed | Failed | Skipped | Blocked |
|-----------|--------|--------|---------|---------|
| AC1 - after Confirm the Register change preview is open again, with the ticked withdrawals, and applying succeeds | 1 | 0 | 0 | 0 |
| AC2 - a load of the local step-up page keeps the saved route and draft; other loads drop them | 1 | 0 | 0 | 0 |
| AC3 - the refused command is never replayed | 1 | 0 | 0 | 0 |
| AC4 - "Not now" leaves nothing restorable | 1 | 0 | 0 | 0 |
| AC5 - gates (not a browser criterion) | 0 | 0 | 1 | 0 |

## Detailed Findings

Test data: as SMO B, a VA Submission was started for DEV-004 (Draft). As VA POC 1 (signed in at 01:21:58), a Register file without DEV-004 was uploaded. The preview showed "0 to add, 0 to update, 1 to retire, 5 unchanged, 0 blocked. In-flight work: 1 to withdraw" with a tick box "Withdraw the Draft VA Submission of DEV-004" under Will withdraw. Apply stayed disabled until the box was ticked. The box was ticked, and Apply was pressed 5 min 20 s after the sign-in.

### AC4 - "Not now" leaves nothing restorable
**Status:** PASS
**Evidence:**
1. Apply answered with the "Sign in again to continue" dialog (audit: `request-refused`, reason `step-up-required`, intent `register-apply`, 403).
2. "Sign in again" went to `/dev-step-up` ("Confirm it is you"). Audit: `step-up-started`.
3. "Not now" returned to `/va-poc/register`: no "Preview of the changes", no "Apply these changes" button, no step-up notice, and `sessionStorage` held neither `annualva.stepUp.route` nor `annualva.stepUp.draft`. DEV-004 was still Active.

### AC2 - the local step-up page keeps the saved route and draft
**Status:** PASS
**Evidence:** On `/dev-step-up`, before either button was pressed, `sessionStorage` held `annualva.stepUp.route` (`/va-poc/register`) and `annualva.stepUp.draft` (intent `register-apply`, the preview id, and `withdraw` with the id of DEV-004's VA Submission). Seen on both visits. Loading `/va-poc/register` without `?stepup=` ("Not now") dropped both. After the `?stepup=done` return both entries were gone from `sessionStorage` at once, as the restructured code intends, and the preview was restored from memory.
**Not covered:** an entry older than a round trip (15 minutes), and the drop when the app finds there is no session.

### AC1 - after Confirm the preview is open again, with the ticked withdrawals, and applying succeeds
**Status:** PASS
**Evidence:**
1. The file was uploaded again (the session was still stale), the box ticked, Apply refused, and "Sign in again" led to `/dev-step-up`.
2. Confirm returned to `/va-poc/register` with the notice "You signed in again. The action was not repeated: do it again to finish." The same preview was open again (same counts), the box "Withdraw the Draft VA Submission of DEV-004" was ticked, and Apply was enabled. Audit: `step-up` success, then `register-preview-viewed` for the same preview id.
3. Pressing "Apply these changes" showed "The Register is updated. 0 added, 0 updated, 1 retired, 5 unchanged. 0 roles granted and 2 revoked. 1 VA Submission withdrawn." Audit: `register-system-retired` and `register-applied`, success, same preview id. DEV-004 read Retired, and SMO B's VA Submission read Withdrawn.

### AC3 - the refused command is never replayed
**Status:** PASS
**Evidence:** The audit log has `step-up` at 01:28:32 and the only `register-applied` of that preview at 01:28:51, after the button was pressed again. Nothing was applied after the first refusal or after "Not now".

### AC5 - gates
**Status:** SKIP (not a browser criterion)
**Reason:** `npm run check` exited 0 on this tree in the correction pass. The back-end suite passed on this checkout on 2026-11-22 (1,358 tests, 0 failures, coverage checks met); no back-end file is changed.

## Observations (not criteria; for triage)
- A preview that only retires a System says "1 to retire" and, on the next line, "Nothing has changed."
- On the return from Confirm the preview is fetched twice (two `register-preview-viewed` audit entries 40 ms apart). The react review attributes this to React's development-mode double effect; it was not checked against a production build.
- The step-up dialog says "You will go to the organisation single sign-on and come back to this screen" under `dev-local`, where the person goes to the local confirmation page.
- After DEV-004 was added back, its Withdrawn VA Submission stays Withdrawn and the SMO's list offers no way to start a new one for it ("Nothing more to do").
- The run was over plain HTTP, so the secure-cookie behaviour over HTTPS was not seen in the browser.
- Local dev data: DEV-004 was retired and then restored by applying the full sample Register again; its Draft VA Submission is now Withdrawn.

## Screenshots

- `artifacts/browser-test/64-screens/02-after-confirm-withdrawal-still-ticked.jpg` (this run: the reopened preview with the withdrawal ticked)
- `artifacts/browser-test/64-screens/01-after-confirm-preview-reopened.jpg` (the earlier run, on the earlier code)

Other evidence is page text, `sessionStorage` and the back end's audit log lines.

## Sign-off

**Result:** PASS
**Blockers:** None.
