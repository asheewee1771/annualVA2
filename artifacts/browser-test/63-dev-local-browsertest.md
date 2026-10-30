# Browser Acceptance Test Report

**Date:** 2026-10-30
**Issue:** ticket 63, `.scratch/annual-va/issues/63-dev-local-profile-persona-sign-in.md` (the front-end criterion), plus one VA Submission walked from SMO to Cyber Manager to VA POC
**Base URL:** `http://localhost:5173` (Vite dev server, proxying the back end on `https://localhost:8443`)
**Run Command:** `docker compose up -d postgres`; `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev-local`; `cd frontend && npm run dev`
**Scope:** all of the above. Browser: Claude in Chrome (agent-browser is not installed, and the built-in browser pane refuses Vite's self-signed certificate).

## Results Summary

| Criterion | Passed | Failed | Skipped | Blocked |
|-----------|--------|--------|---------|---------|
| Ticket 63, front end: `/dev-sign-in`, `/dev-step-up`, banner, offered only when the list answers 200 | 1 | 0 | 0 | 0 |
| End to end: SMO submits, Cyber Manager reviews, VA POC endorses | 1 | 0 | 0 | 0 |

## Detailed Findings

### Ticket 63 front end: `/dev-sign-in` and `/dev-step-up`
**Status:** PASS
**Steps:**
1. The sign-in landing shows "Sign in" and a "Local development: Sign in as a persona" link, which is there because `GET /api/dev/personas` answers 200.
2. `/dev-sign-in` shows the banner "Local development sign-in. This is not the organisation single sign-on: anyone who can reach this application can sign in as any persona." and the six personas (name, role label, email) as buttons. The classification note is kept.
3. Choosing a persona signs in and lands on that role's home: SMO A on `/smo`, Cyber Manager A on `/cyber-manager`, VA POC 1 on `/va-poc`.
4. After five minutes, "Apply these changes" on the System Register answered 403, and the page showed a "Sign in again to continue" dialog. "Sign in again" went to `/dev-step-up?intent=register-apply&returnTo=/va-poc/register`, which says "Confirm it is you" with Confirm and Not now. Confirm returned to the Register screen.
5. The audit log shows, in order: request refused (`step-up-required`), step-up started, step-up completed, all for intent `register-apply`.

**Not covered:** "not offered when the list is not 200" was not run in the browser; the Seam 1 tests cover the 404.

### One VA Submission, SMO to Cyber Manager to VA POC
**Status:** PASS
**Steps:**
1. SMO A: `/smo` lists DEV-001 and DEV-002 as Not Started, DEV-005 as handled outside the app. Start on DEV-001 opened the draft.
2. Uploading the three sample reports ended "Accepted, findings read" after the scan; 15 Findings appeared. Bulk-selecting all 15 and choosing Remediation Planned (plan and target date) gave "15 of 15 Findings have a proposed status". The Burp report asked for a scan date; it was given. Ticking the Declaration and "Submit for review" moved the VA Submission to In Review.
3. Cyber Manager A: the Inbox listed DEV-001 under "Needs your review (1)". The review screen showed one bulk-set batch; "Confirm batch" gave "15 confirmed"; "Pass to endorsement" moved it to Awaiting Endorsement.
4. VA POC 1: the Quarter board showed DEV-001 under Awaiting Endorsement; "Endorse" moved it to Endorsed.

**Test data:** the sample reports in `backend/src/test/resources/samples/` are dated July 2027, so the app correctly rejected them as "dated in the future". Dated copies (28 Oct 2026) were used instead, kept outside the repository.

## Observations (not criteria; for triage)
- After a step-up on the Register screen, the user returns to an empty upload form: the preview and the apply do not resume (the audit log has no "applied" entry), so the file has to be uploaded again. `frontend/src/api/stepUp.ts` says it keeps where the person was and any form.
- After submit, the SMO's "Prepare the VA Submission" screen still shows its upload and Save controls with the state "In Review". Whether they are inert was not tried.
- A report upload's status ("Received, waiting for the malware scan") updates by itself within about 10 seconds; the first look after a few seconds can seem stuck.
- Not exercised: the local mail relay `Event=email` line, the `.bounce` rejection, a quarantined upload through the page. Earlier hand runs through the API covered the scanner and a replayed step-up.

## Screenshots
None saved. Screenshots from the Chrome tab timed out repeatedly; evidence is the page text, DOM and the audit log.

## Sign-off
**Result:** PASS
**Blockers:** none for ticket 63. The self-signed certificate keeps the built-in browser pane from the app; the run used a throwaway plain-HTTP Vite config (deleted afterwards), so the secure-cookie behaviour over HTTPS was not seen in the browser.
