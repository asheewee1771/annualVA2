# 27: Cyber Manager inbox and Quarter board

**Spec:** [Annual VA spec](../spec.md), stories 48, 49.
**Release:** 1
**What to build:** A Cyber Manager sees the VA Submissions for the Systems assigned to them, Overdue first, and a Quarter board with one column per state.
**Blocked by:** 26 (Overdue is a flag: soft windows, nothing locks)
**Status:** ready-for-agent
**Effort (orchestrator estimate):** about 10.0 developer days across stories 48 (5.0), 49 (5.0); `*` marks the critical path. See `reports/dev-report.md`.

- [x] `GET /api/dashboards/cm-inbox?cycle=` returns the submissions of the Systems assigned to the caller in the Register, ordered Overdue first then by due date, with state, counts of confirmed and pending Findings and a blocked marker where segregation of duties will apply.
- [x] `GET /api/dashboards/quarter-board?cycle=&quarter=` returns the Systems of the Quarter grouped by state (Not Started through Endorsed, Exempted, Withdrawn); a Cyber Manager sees only Systems assigned to them in the Register.
- [x] An SMO calling the Cyber Manager inbox gets 403; the front end shows the Inbox and Quarter board tabs.
- [x] Every behaviour above is proven by Seam 1 tests through the HTTP API on a real PostgreSQL, with only the boundary adapters faked; the back-end and front-end gates pass.

## Comments

### Reviewer log (do-work reviewer, rounds 1-2)

## Findings

| Finding | Required action | Status |
| --- | --- | --- |
| Story 48 and the ticket say the inbox lists the VA Submissions of the caller's Systems; as built it also lists Not Started Systems (no VA Submission), Overdue Not Started first, under Waiting on others. The spec does not settle it.<br><br><details><summary><strong>Verify in</strong></summary><br><code>ReviewedSystems.in</code><br><code>CmInbox</code><br><code>ADR 0034 item 2</code></details> | Human decision: (a) keep Not Started Systems in the inbox [reviewer recommends]; (b) started VA Submissions only, Not Started visible on the board. | Resolved |
| Naming every state forced SMO inbox choices: Awaiting Endorsement waits on the VA POCs due the Quarter's last day, Exemption Requested waits with no due date, and Endorsed, Exempted, Withdrawn and Not Endorsed are Done with nothing next. The due dates follow ADR 0033 item 2 (current_window_due); the grouping and labels (Not Endorsed shown as Done) are not settled anywhere.<br><br><details><summary><strong>Verify in</strong></summary><br><code>SmoInbox.started</code><br><code>ADR 0034 item 6</code></details> | Human decision: (a) keep as built [reviewer recommends]; (b) give Not Endorsed (and Withdrawn) their own heading or label instead of Done. | Resolved |


## Human decisions

| Area | Decision | Chosen action | Reuse when |
| --- | --- | --- | --- |
| backend dashboard, ADR 0034 | Story 48 says the inbox shows VA Submissions; Not Started Systems have none (KB unavailable). | Do not follow KB recommendation: no KB guidance; user chose (a) on 2026-10-24, list every System in the review scope including Not Started, under Waiting on others, Overdue first. | Any list of a role's Systems that could leave out Systems with no VA Submission. |
| backend dashboard SmoInbox, ADR 0034 item 6 | Not Endorsed is a failed outcome but is listed under Done (KB unavailable). | Do not follow KB recommendation: no KB guidance; user chose (a) on 2026-10-24, keep the grouping and let ticket 41 decide Not Endorsed's heading or label. | Any finding about how the SMO inbox groups or labels a final state before its ticket lands. |


## Full do-work log

Open locally: `artifacts/do-work/27-cyber-manager-inbox-and-board.html`

Includes reviewer findings and human decisions.

### Verification (2026-10-24)

**Implemented** (commits `66e61fc`, `c28c6ce`, `37ecba1`, `f1fed77`, `8a56f80`):
- **Cyber Manager inbox.** `GET /api/dashboards/cm-inbox?cycle=` (Cyber Manager only) lists every System in the caller's review scope, Not Started included. It is ordered Overdue first, then by the day its current window is due (no-window states last), then Quarter, then System ID. Each item has its state, due dates, `confirmed` and `awaitingReview` counts and a `sodConflict` marker. Quarters handled outside the app are listed apart.
- **Quarter board.** `GET /api/dashboards/quarter-board?cycle=&quarter=` (Cyber Manager only until ticket 33) has ten columns, one per state in the spec's state-table order. `quarter` must be 1 to 4, otherwise 400. When it is omitted, the board opens on today's Quarter.
- **One reader.** `DashboardSystems` feeds the SMO inbox, the Cyber Manager inbox and the board, and every due date comes from the one `current_window_due` rule. As a result the SMO inbox now dates a Returned for Rework VA Submission on `review_due`, like the Overdue flag. `SubmissionState` names all nine states, and the SMO inbox groups the later ones.
- **Screens.** The Cyber Manager area has Inbox and Quarter board tabs with Overdue and segregation-of-duties flags, the counts, and Cycle and Quarter selectors. The board scrolls with the keyboard. The prepare screen labels every state.
- **Docs.** ADR 0034 records the design; ADRs 0019 and 0033 and the README are amended. Ticket 41 gained a note.

**Decisions made**
- The Cyber Manager inbox lists Not Started Systems too (option (a), docs/agents/reviewer-decisions.md, 2026-10-24).
- The SMO inbox puts the later states under Waiting on others or Done until ticket 41 decides Not Endorsed's heading (option (a), 2026-10-24).
- The reviewer settled from the spec: ten board columns (story 49), and the board stays Cyber Manager only until ticket 33.

**Verification performed**
- Back end `mvnw -B -ntp clean verify`: 799 tests, 0 failures, ArchUnit and JaCoCo met. Front end `npm run check`: passed.
- Reviewer: round 1 Must-fix 2 (the front end knew three states; the board's access test lacked VA POC and no-role) and two human decisions; round 2 Must-fix 0. Its log is above.
- `/code-reviewer` round 1: FAIL on two thermo-nuclear Highs (the SMO inbox had its own due-date mapping that disagreed with the shared rule; the Cyber Manager reader copied the SMO inbox's). After one correction pass, round 2 is a PASS: im8 PASS; build, thermo-nuclear, code-review, semgrep, react and springboot WARN; no Critical or High finding. Report: `artifacts/code-reviewer/27-cyber-manager-inbox-and-board-compliance.html`.
- Selective mutation: 15 mutants, 14 killed. M05 (inbox reading the VIEW scope instead of REVIEW) survived at first; the inbox and board tests now cover a Cyber Manager who is also the SMO of a System they don't review. The survivor M12 is equivalent. Report: `artifacts/mutation-testing/27-cyber-manager-inbox-and-board-mutation.md`.
- Two proposal tests now read their own audit entries by System (`66e61fc`), so later-dated entries cannot push them off the shared page.

All checklist boxes above are ticked.

**Not verified, and open**
- semgrep, im8 and react were not rerun after the second correction pass (the shared reader and the due-date change).
- The Withdrawn column stays empty for a Cyber Manager until ticket 43, because a retired System's grants are revoked. `sodConflict` reads only actual participation; ticket 28 adds the guard.
- Eleven other tests still read the newest 200 global audit entries and can pass vacuously as the suite grows.
- No browser run, because sign-in needs `../mock-m-sso`.
- Medium and Low findings remain open in the compliance report.
