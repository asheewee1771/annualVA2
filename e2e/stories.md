# Stories and acceptance criteria the scenarios bind to

The spec (`.scratch/annual-va/spec.md`) writes each user story as one sentence and gives it no numbered acceptance
criteria. Each scenario therefore binds to one story and to one criterion, `AC1`, written here from the story's own
words and narrowed to what Release 1 delivers where a ticket says so. The user approved this table on 2026-11-22, with
the bulk bar staying in view (US37) and the Disputed count (US38) put back.

The table is read by the harness (`scripts/lib/stories.mjs`). A scenario's Allure feature and suite come from it, and
`scripts/check-evidence.mjs` fails a run in which a scenario's title, sub-suite or the criterion line of its
description is not the AC1 below, word for word. Change a criterion here and in its spec file together, never in one
alone. Keep the columns in this order and no `|` inside a cell.

| Story | Title | Feature | AC1 | Narrowed by | Spec file |
|---|---|---|---|---|---|
| US36 | Findings list with filter chips, tick boxes and select-all | SMO submission spreadsheet | The Findings list offers the filter chips Needs attention, All, Disputed and Critical/High, a tick box on every Finding, and a select-all that ticks every Finding the chosen filter shows. | Ticket 20: the Carried Over and Target Date Missed chips arrive in Release 2 | `tests/us36-filter-chips-and-select-all.spec.ts` |
| US37 | Bulk bar for the selected Findings | SMO submission spreadsheet | A bulk bar that stays in view while the Findings list scrolls applies one status, one detail and one target date to every selected Finding in one step. | | `tests/us37-bulk-bar.spec.ts` |
| US38 | Progress bar of proposed Finding Statuses | SMO submission spreadsheet | A progress bar shows how many Findings have a proposed status, how many are still to do and how many are Disputed, and moves when a status is proposed or a Finding is Disputed. | Ticket 20: the Carried Over and Target Date Missed counts arrive in Release 2 | `tests/us38-progress-bar.spec.ts` |
| US40 | Submit enabled only when complete | SMO submission spreadsheet | Submit stays disabled until every Finding has a proposed status and the Declaration is ticked. | | `tests/us40-submit-enabling.spec.ts` |
| US60 | Guided rework queue, one Finding at a time | Rework queue | A VA Submission Returned for Rework opens as a guided queue that shows one Finding at a time, the Disputed Findings first, and moves to the next when one is saved. | Ticket 31: the Target Date Missed and Carried Over stages arrive in Release 2 | `tests/us60-rework-queue.spec.ts` |
| US61 | The Cyber Manager's Dispute comment on each Finding | Rework queue | The rework queue shows, on each Disputed Finding, the comment the Cyber Manager wrote when Disputing it. | | `tests/us61-dispute-comment.spec.ts` |
| US62 | Keyboard shortcuts in the rework queue | Rework queue | In the rework queue a key from 1 to 4 picks a status, and Enter saves it and moves on to the next Finding. | | `tests/us62-rework-shortcuts.spec.ts` |
| US18 | The SMO/Tech Lead's inbox | Role dashboards | The SMO/Tech Lead's inbox lists their own Systems of the current Quarter, each with what to do next and the date it is due. | | `tests/us18-smo-inbox.spec.ts` |
| US48 | The Cyber Manager's action inbox | Role dashboards | The Cyber Manager's inbox lists the Systems the System Register assigns to them and puts a VA Submission that was submitted under Needs your review. | Overdue first needs a clock dev-local does not have; it stays at Seam 1 | `tests/us48-cyber-manager-inbox.spec.ts` |
| US49 | The Cyber Manager's Quarter board | Role dashboards | The Cyber Manager's second tab is a Quarter board that shows their Systems in one column per state. | | `tests/us49-cyber-manager-quarter-board.spec.ts` |
| US64 | The VA POC's Quarter board and Cycle matrix | Role dashboards | The VA POC has two tabs: a board of the current Quarter, and a Cycle matrix with a strip of all four Quarters and one row per System with its milestones and when each is due. | | `tests/us64-va-poc-board-and-cycle-matrix.spec.ts` |
| US45 | Exemption requests waiting on the VA POC's dashboard | Role dashboards | The VA POC's dashboard has a list of the Exemption requests that are waiting, each with what it rests on and the controls to decide it. | | `tests/us45-exemption-requests-waiting.spec.ts` |
| US81 | Management summary export with no vulnerability details | Management summary export | A VA POC asks for the management summary, downloads it as an Excel workbook that lists the Systems, and the workbook holds no vulnerability details. | The PDF and the summary's counts stay at Seam 1 | `tests/us81-management-summary-export.spec.ts` |
| US86 | Exports carry their classification | Management summary export | A management summary that covers every System is marked Confidential where it is listed and in the file itself: the page header and footer and the first row of every sheet. | The PDF's diagonal watermark stays at Seam 1 | `tests/us86-export-classification-watermark.spec.ts` |

Business rules are not proved here (ADR 0009, spec "Testing Decisions"): which Finding comes first in the queue, when a
VA Submission is Overdue, what a status requires and what the summary counts are Seam 1's. So are the access rules: a
scenario reads that a person's own Systems are listed, and never that someone else's are not (the user's decision of
2026-11-22). A scenario reads what the screen shows after a person acts, and stops there.
