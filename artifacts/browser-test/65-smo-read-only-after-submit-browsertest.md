# Browser Acceptance Test Report

**Date:** two runs. Run A: 2026-11-21 17:57 to 18:02. Run B: 2026-11-22 01:30 to 01:35 (Singapore time), on the final code.
**Issue:** ticket 65, `.scratch/annual-va/issues/65-smo-screen-read-only-after-submit.md`
**Base URL:** `http://localhost:5173` (Vite dev server from the ticket 65 worktree, proxying the back end on `https://localhost:8443`)
**Run Command:** `docker compose up -d postgres`; back end `backend\mvnw.cmd -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev-local` (the back end is identical in both trees); front end `npm run dev --prefix .claude/worktrees/agent-a275cfa461b32233f/frontend -- --config vite.http.local.config.ts`
**Scope:** all criteria a browser can show (1 to 5, and the flow of 7).
**Tree under test:** `HEAD` ba45b05 plus the uncommitted ticket 65 change.
- Run A: `Editable.tsx`, `SubmissionPrepareScreen.tsx`, `styles.css` after the first correction pass.
- Run B: the same plus `frontend/src/api/submissions.ts` (a type-only change; the emitted code was reported byte-identical) and the focus effect that remembers the previous state (`wasEditable`). The served `SubmissionPrepareScreen.tsx` contained `wasEditable`.
**Browser:** the built-in browser pane of the Claude desktop app, over a throwaway plain-HTTP Vite config (deleted afterwards). agent-browser is not installed.

## Results Summary

| Criterion | Passed | Failed | Skipped | Blocked |
|-----------|--------|--------|---------|---------|
| AC1 - every changing control is disabled in In Review and in one more state | 1 | 0 | 0 | 0 |
| AC2 - a disabled changing control reads as unavailable, not busy | 1 | 0 | 0 | 0 |
| AC3 - the screen says near the top that it is read-only and why | 1 | 0 | 0 | 0 |
| AC4 - read-only straight after Submit without a reload; editable again after a return | 1 | 0 | 0 | 0 |
| AC5 - downloads, filters and paging stay usable | 1 | 0 | 0 | 0 |
| AC6 - server refusals unchanged (not a browser criterion) | 0 | 0 | 1 | 0 |
| AC7 - the flow SMO → Cyber Manager → VA POC walked again | 1 | 0 | 0 | 0 |

## Detailed Findings

### AC1 - every changing control is disabled
**Status:** PASS
**Evidence:** In every state below, every `<Editable>` group was disabled and no control inside one was live. The only live controls were the filter chips, the Download buttons and, for a conditional Exemption, its own "Record that the VA happened" form, which sits outside `<Editable>` by design.

| State | VA Submission | Groups disabled | Run |
|---|---|---|---|
| In Review | DEV-002, straight after submit and after resubmit | 7 of 7 (20 controls) | A, B |
| Awaiting Endorsement | DEV-002 | 7 of 7 | B |
| Endorsed | DEV-001, with "All" showing its 15 Findings | 46 of 46 | A |
| Endorsed | DEV-001, default filter | 15 of 15 | B |
| Exemption Requested | DEV-003 | 9 of 9 | B |
| Exempted (conditional) | DEV-003 | 9 of 9 | B |
| Withdrawn | DEV-004 | 9 of 9 | B |

In the Endorsed case of run A the disabled controls were 18 selects, 12 submit buttons, 3 Replace buttons, 3 date inputs, 3 text inputs, 6 file inputs and 16 tick boxes.

### AC2 - disabled reads as unavailable, not busy
**Status:** PASS (run A; the stylesheet is unchanged since)
**Evidence:** Computed styles of every disabled control inside a read-only group: cursor `not-allowed`, muted text `rgb(91, 100, 112)` on a grey fill, dashed border, opacity 1 (buttons, selects, text, date and file inputs, textareas). Tick boxes keep the browser's disabled look with `not-allowed`. In the dark scheme: muted text `rgb(163, 172, 184)` on a lighter fill than the page, dashed border, `not-allowed`; the live Download button keeps its solid border and pointer. In Draft and Returned for Rework the groups are live, and a button disabled for its own reason (nothing to save yet) keeps the busy look (opacity 0.6, `progress`), unchanged.
**Note:** in the dark scheme the dashed border is faint; the fill and the text colour carry the difference.

### AC3 - the notice near the top
**Status:** PASS
**Evidence:** Directly under the header card, each ending "Downloads, filters and paging still work.":
- In Review: "Read-only. It was submitted for review and is with the Cyber Manager. If it is returned for rework, you can change it again."
- Awaiting Endorsement: "Read-only. It was submitted and its Findings are all confirmed; it is now with the VA POCs for endorsement. If it is returned for rework, you can change it again."
- Endorsed: "Read-only. It is decided: a VA POC endorsed it. The work below is kept for audit."
- Exemption Requested: "Read-only. It is frozen: your Exemption request is waiting on a VA POC's decision, and nothing can be changed or submitted until then. If it is rejected, this VA Submission goes back exactly as it was." It was the only notice on the screen.
- Exempted, conditional: "Read-only. A VA POC approved the Exemption on a scheduled VA, so it stays conditional until that VA is recorded and confirmed: where that stands is just below. The work prepared here is kept for audit and cannot be changed." The "Record the scheduled VA" panel followed directly.
- Withdrawn: "Read-only. It is withdrawn: its System was retired before endorsement. The work below is kept for audit."
- Draft and Returned for Rework: no read-only notice.
**Not covered:** Not Endorsed (it needs a VA Cycle to close) and an Exemption that is no longer conditional ("It is decided: a VA POC approved the Exemption").

### AC4 - straight after Submit, and after a return
**Status:** PASS
**Evidence:**
1. Run A: as SMO A, a VA Submission was started for DEV-002 (Dependency-Check and Burp Suite marked not applicable, Secure Configuration entered by hand with one Manually Entered Finding `BT65-RULE-1` proposed as Remediation Planned). The Declaration was ticked and "Submit for review" pressed. Without a reload (a marker set on the page before the click was still there) the state read "In Review", the notice appeared, all 7 groups were disabled, and the Declaration card read "Submitted for review on 21 Nov 2026, 5:59 pm. This VA Submission is read-only now." The focus moved to the notice.
2. Run A: as Cyber Manager A, the Finding was Disputed and "Return for rework" pressed. As SMO A again, the screen read "Returned for Rework" with the rework notice, no read-only notice, 0 of 10 groups disabled, and the focus was not moved.
3. Run B, on the final focus effect: the Finding was proposed again, the Declaration ticked and "Resubmit for review" pressed. Without a reload the state read "In Review", all 7 groups were disabled, and the focus was on the notice (the page scrolled to it).
4. Run B: opening a VA Submission that is already read-only (Endorsed, Awaiting Endorsement, Exemption Requested, Exempted) left the focus on the page body.
**Note:** the return is made by another person, so "straight after" was seen on the SMO's next load of the screen, not while it was open.

### AC5 - downloads, filters and paging
**Status:** PASS
**Evidence:** On the Endorsed VA Submission the "All" filter chip worked (15 Findings listed) and the six Download buttons were enabled.
**Not covered:** no file was downloaded, and there was no second page of Findings to page to.

### AC6 - server refusals unchanged
**Status:** SKIP (not a browser criterion)
**Reason:** a disabled control cannot be pressed; the Seam 1 tests cover the 409, and the back-end suite passed on this tree on 2026-11-22 (1,358 tests, 0 failures).

### AC7 - the flow walked again
**Status:** PASS
**Evidence:** DEV-002 went SMO A (submit) → Cyber Manager A (Dispute, return for rework) → SMO A (resubmit) → Cyber Manager A (confirm both not-applicable justifications and the Finding, "Pass to endorsement": Awaiting Endorsement) → VA POC 1 ("Endorse": Endorsed). `npm run check` exited 0 on the final tree.

## Observations (not criteria; for triage)
- Test set-up note: form fields (modes, justifications, the Manually Entered Finding, proposals, the Cyber Manager's and VA POC's buttons, the Exemption request) were filled by script through the page's own controls. The Declaration tick and the Submit and Resubmit presses were real clicks.
- A not-applicable justification is shown twice on the read-only screen: once as text and once in the disabled textarea.
- After Submit the page scrolls to the top because the focus moves to the notice. A person who was reading the Declaration card loses their place.
- Not checked: a real screen reader, Firefox, Safari, forced-colours mode.
- Local dev data after both runs: DEV-002 Endorsed (one Manually Entered Finding `BT65-RULE-1`); DEV-003 Exempted on a scheduled VA of 15 Dec 2026, not yet recorded; DEV-004's Draft Withdrawn (by ticket 64's test).

## Screenshots

- `artifacts/browser-test/65-screens/01-endorsed-findings-disabled.jpg` (run A)
- `artifacts/browser-test/65-screens/02-straight-after-submit-read-only.jpg` (run A)
- `artifacts/browser-test/65-screens/03-returned-for-rework-editable-again.jpg` (run A)
- `artifacts/browser-test/65-screens/04-endorsed-dark-scheme.jpg` (run A)
- `artifacts/browser-test/65-screens/05-exemption-requested-frozen.jpg` (run B)
- `artifacts/browser-test/65-screens/06-exempted-conditional.jpg` (run B)

## Sign-off

**Result:** PASS (Not Endorsed and a confirmed Exemption were not reached; both need a VA Cycle close or a recorded VA)
**Blockers:** None.
