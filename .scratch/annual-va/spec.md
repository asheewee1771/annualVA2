# Annual VA: early-draft spec (TRD)

Status: ready-for-agent
Compliance: **approved (Gate 1)** on 2026-09-29 by Augustine Ang (PO), against [the passing report](../../artifacts/spec-compliance/spec-compliance-annual-va-early-draft-spec-trd-2026-09-29-0048.html) (0 FAIL, 25 PASS, 3 DEFERRED, 96 N/A). Approved on the understanding that assumptions A-1 to A-9 are still to be confirmed by the cyber team ([checklist](issues/11-confirm-policy-facts.md)). If any answer differs, re-run `/spec-compliance` on the affected controls.
Date: 2026-09-28
Origin: compiled with `/to-spec` from the wayfinder map [Annual VA: map](map.md). All terms follow the glossary in [CONTEXT.md](../../CONTEXT.md).
Stage: **early-draft TRD** (Phase 1, Path 2 handoff). The Architect/Dev team refines it in Phase 2. Items marked **(assumption A-n)** are unconfirmed; see *Further Notes → Assumptions to confirm*.

## Problem Statement

Once a year, every in-scope System in the organisation (about 40–48 of them) must prove it has no outstanding vulnerabilities or configuration deviations. The work is spread over four Quarters of about 10–12 Systems each. For each System, the SMO/Tech Lead has to supply three Scan Reports (OWASP Dependency-Check, Secure Configuration and Burp Suite) and a Declaration. A Cyber Manager then reviews it, and a VA POC endorses it.

Today this happens outside any system. The VA POC chases SMOs by hand. Nobody can easily see which Systems in a Quarter have submitted, are stuck in review, or are overdue. Cyber Managers can't tell whether every individual Finding has been dealt with. Findings that were accepted or deferred last year aren't reliably followed up this year. And the scan reports themselves are a sensitive target list of real, unfixed weaknesses, yet they circulate as loose files.

## Solution

An internal web app (React front end, Spring Boot back end) that runs the whole VA Cycle.

**Planning.** The VA POC loads the **System Register** from a fixed Excel template, which assigns each System to a **Quarter**. The app then tracks every System through its 13-week Quarter.

**Submitting.** The SMO/Tech Lead starts a **VA Submission** and uploads the three Scan Reports in machine-readable form. The app malware-scans the files, reads every **Finding** out of them, and pre-fills statuses carried over from the System's last Endorsed submission. The SMO proposes a **Finding Status** for every Finding, individually or in bulk, then ticks the Declaration and submits.

**Reviewing and endorsing.** The Cyber Manager confirms or **Disputes** each Finding and can return the submission for rework. Once every Finding is confirmed, one of the two VA POCs endorses it. Systems that had, or will soon have, a separate VA can be **Exempted**.

**Keeping people informed.** Deadlines are soft. Missing one sets **Overdue** and triggers reminders by email and in-app notification. Each role gets a dashboard for its own work. Management gets a detail-free summary export, Cyber Managers a detailed export, and auditors an evidence pack plus a read-only Auditor role.

**Protecting the data.** Everything is encrypted at rest, kept in an append-only audit log, and retained for the VA Cycle plus 3 years. No person may act at more than one step on the same VA Submission.

## User Stories

### VA Cycle and System Register

1. As a VA POC, I want to download a fixed Excel template for the System Register (System ID, System Name, SMO/Tech Lead email, Cyber Manager email, Quarter), so that I plan the year, including who reviews each System, in a tool I already use.
2. As a VA POC, I want to upload the filled-in template and see a preview of every change before it applies, so that I don't break in-flight work by accident.
3. As a VA POC, I want a re-upload to update and add Systems matched by System ID rather than replace the Register, so that history and in-flight VA Submissions survive.
4. As a VA POC, I want a System that is missing from a new upload to be marked retired rather than deleted, so that its past VA Submissions stay auditable.
5. As a VA POC, I want the preview to group the effects on in-flight work under *Will withdraw*, *Will move*, *Will transfer* and *Blocked*, so that I understand the consequences before confirming.
6. As a VA POC, I want to confirm each retirement of a System that has an in-flight VA Submission, so that nothing is withdrawn silently.
7. As a VA POC, I want a retired System's in-flight VA Submission to become Withdrawn, read-only and excluded from counts and reminders, so that dashboards stay accurate and the audit record stays intact.
8. As a VA POC, I want to move a Not Started or Draft System to another Quarter, with its Draft intact, so that I can rebalance the Quarters.
9. As a VA POC, I want a move of a System whose submission is In Review or later to be blocked, so that I don't pull work out from under a Cyber Manager.
10. As a VA POC, I want a System added partway through its Quarter to get its own two-week Submission Window and two-week Review Window from the date it was added, so that it isn't Overdue the moment it appears.
11. As a VA POC, I want the preview to suggest the next Quarter when fewer than 4 weeks remain, so that I don't set impossible deadlines.
12. As a VA POC, I want a System whose SMO email doesn't match any sign-in yet to show as Unlinked SMO, so that I can chase the right person.
13. As a VA POC, I want a System whose notification emails bounce to show as SMO Unreachable, so that I can fix the Register.
14. As a VA POC, I want the preview to block a Register row with no Cyber Manager, or with the System's own SMO as its Cyber Manager, so that every System has an independent reviewer.
15. As a VA POC, I want a change of Cyber Manager partway through to hand the submission to the new Cyber Manager, who re-reviews only what isn't yet confirmed, with the old Cyber Manager losing access, so that reviews continue when people change.
16. As a VA POC, I want the VA Cycle to run February to January in four 13-week Quarters, each starting on the first Monday of February, May, August and November, named after its starting year, so that the app matches our planning year.
17. As a VA POC, I want a VA Cycle to close 4 weeks after its Q4 ends, or earlier if I choose, with a Cycle selector while two Cycles overlap, so that late Q4 endorsements still have time.

### Starting and preparing a VA Submission

18. As an SMO/Tech Lead, I want to see my Systems for the current Quarter, with what I need to do and by when, so that I know where to start.
19. As an SMO/Tech Lead, I want to start the VA Submission for my System myself, so that I control when the work begins.
20. As an SMO/Tech Lead, I want to upload one or more files for each of the three Scan Report types, so that a System with several components or hosts still has one Scan Report of each type.
21. As an SMO/Tech Lead, I want the app to accept only the machine-readable formats (Dependency-Check XML or JSON; ARF/XCCDF results XML or `.nessus` for Secure Configuration; the Burp XML issue export), so that my Findings can be read automatically.
22. As an SMO/Tech Lead, I want to attach PDF or HTML reports as supporting evidence only, so that I can include what my tool produces for people to read.
23. As an SMO/Tech Lead whose tool can't export a machine-readable format, I want to tick a box saying so and enter Findings by hand, so that I can still submit. They're marked Manually Entered.
24. As an SMO/Tech Lead, I want every upload to be malware-scanned, type-checked and schema-checked before it's read, so that a bad file is rejected before it can do harm.
25. As an SMO/Tech Lead, I want the scan date and target pre-filled from the file for me to confirm, so that I only correct what the tool got wrong (Burp only records its export time).
26. As an SMO/Tech Lead, I want submitting to be blocked if **any file** is more than 90 days old at submission, or dated in the future, with the message naming that file, so that I don't submit stale evidence.
27. As an SMO/Tech Lead, I want to be told plainly when a malware scan times out and my upload was rejected, so that I know to upload again.
28. As an SMO/Tech Lead, I want every Finding read from my reports listed with its report, identifier, package or rule, severity and source file, so that I can deal with each one.
29. As an SMO/Tech Lead, I want failed Secure Configuration checks (XCCDF `fail`, Nessus `FAILED` and `WARNING`) to become Findings and passed or not-applicable checks to be ignored, so that I only handle real deviations.
30. As an SMO/Tech Lead, I want every Burp issue to become a Finding with its confidence shown, so that I can mark Tentative ones False Positive with a reason.
31. As an SMO/Tech Lead, I want Findings from last time pre-filled with their earlier status and marked Carried Over, so that I don't retype justifications I already wrote.
32. As an SMO/Tech Lead, I want to re-confirm Carried Over Findings one at a time or in bulk, so that the annual re-confirmation is quick.
33. As an SMO/Tech Lead, I want a Remediation Planned Finding whose target date has passed to be flagged Target Date Missed, and to be made to give a new date with a reason or choose another status, so that deferred fixes don't roll on unnoticed.
34. As an SMO/Tech Lead, I want Risk Accepted Findings to need re-justifying every VA Cycle, starting from last year's text, so that accepted risks are reviewed once a year.
35. As an SMO/Tech Lead, I want to propose a Finding Status for every Finding, with the field each status requires (Remediated: evidence; False Positive: justification; Risk Accepted: reason, plus the approver's name and role as free text and an optional approval memo; Remediation Planned: plan and target date), so that the Cyber Manager has what they need.
36. As an SMO/Tech Lead, I want a spreadsheet-style list with filter chips (Needs attention, All, Carried Over, Target Date Missed, Disputed, Critical/High), tick boxes and select-all, so that I can work through 100–200 Findings.
37. As an SMO/Tech Lead, I want a sticky bulk bar that applies one status, one detail and one target date to every selected Finding, so that I can deal with groups such as all the CVEs in one package in one step.
38. As an SMO/Tech Lead, I want a progress bar showing how many Findings have a proposed status, with counts of Carried Over, Target Date Missed and Disputed, so that I know how much is left.
39. As an SMO/Tech Lead, I want to replace a Scan Report and have matching Findings keep their status and history, new Findings added, and missing ones set to Remediated with the rescan as evidence, so that uploading a rescan never loses work.
40. As an SMO/Tech Lead, I want Submit to stay disabled until every Finding has a proposed status and I've ticked the fixed Declaration text, so that I can't submit something incomplete.
41. As an SMO/Tech Lead, I want the exact Declaration wording I agreed to stored with my submission, so that the record shows what I declared.
42. As an SMO/Tech Lead, I want to keep working after the Submission Window has passed, with my submission marked Overdue, so that a missed deadline doesn't lock me out.

### Exemptions

43. As an SMO/Tech Lead, I want to request an Exemption at any time before endorsement, with evidence of a VA done within the 3 months before the Quarter or one scheduled within the 3 months after, so that my System isn't assessed twice.
44. As an SMO/Tech Lead, I want my submission frozen while the Exemption request is pending, and returned to exactly its previous state if the request is rejected, so that I don't lose work.
45. As a VA POC, I want a list of Exemption requests waiting on my dashboard, so that I can decide them promptly.
46. As a VA POC, I want to approve or reject an Exemption, with approval making the submission Exempted while keeping its work for audit, so that the outcome is clear and traceable.
47. As a VA POC, I want an Exemption based on a future VA to stay conditional, and, if that VA doesn't happen within 3 months, the System to be flagged and returned to its previous state (or Not Started) with new 2-week windows from the date it's flagged and endorsement due by the later of its Quarter's end and the Cycle's close, so that Exemptions can't be used to dodge the VA.

### Review by the Cyber Manager

48. As a Cyber Manager, I want an action inbox of VA Submissions for the Systems assigned to me in the System Register, with Overdue ones first, so that I work in the right order.
49. As a Cyber Manager, I want a Quarter board as a second tab, with one column per state, so that I can see the whole Quarter at once.
50. As a Cyber Manager, I want to confirm each proposed Finding Status, or mark it Disputed with a comment, so that I only send back what's wrong.
51. As a Cyber Manager, I want Bulk-set Findings grouped by batch, and to confirm or Dispute a whole batch, so that reviewing bulk work doesn't take longer than doing it.
52. As a Cyber Manager, I want Manually Entered Findings clearly marked, so that I check them more closely.
53. As a Cyber Manager, I want to see a Carried Over Finding's earlier status, who confirmed it, and how many cycles it's been open (for example "Risk Accepted, 3rd cycle"), so that I can challenge long-running Deviations.
54. As a Cyber Manager, I want Target Date Missed Findings highlighted, so that I notice fixes that keep slipping.
55. As a Cyber Manager, I want to return a submission for rework with the Disputed Findings attached, and have confirmed Findings stay confirmed, so that the next review is shorter.
56. As a Cyber Manager, I want to re-review only the Findings that changed when a submission comes back, including after a VA POC return, so that I don't repeat work.
57. As a Cyber Manager, I want the submission to go to the VA POCs only once every Finding is confirmed, so that nothing reaches endorsement unchecked.
58. As a Cyber Manager, I want to be blocked from reviewing a VA Submission I acted on at another step, so that segregation of duties holds even if I also hold another role.
59. As a Cyber Manager, I want a detailed export of Findings (CVE, rule or issue, severity, Finding Status and target date) for the Systems I review, so that I can analyse them outside the app.

### Rework by the SMO/Tech Lead

60. As an SMO/Tech Lead whose submission was Returned for Rework, I want a guided queue that takes me one Finding at a time (Disputed, then Target Date Missed, then Carried Over to re-confirm, then new Critical/High, then new Medium/Low), so that I fix exactly what was raised.
61. As an SMO/Tech Lead, I want to see the Cyber Manager's Dispute comment on each Finding, so that I know what to change.
62. As an SMO/Tech Lead, I want keyboard shortcuts in the rework queue (1–4 picks a status, Enter saves and moves on), so that rework is fast.
63. As an SMO/Tech Lead, I want a return from the VA POC to come back to me, and my resubmission to go through the Cyber Manager again, so that the chain of confirmation stays whole.

### Endorsement by a VA POC

64. As a VA POC, I want a board for the current Quarter and a matrix of the whole VA Cycle (a strip showing all four Quarters, then one row per System with its milestone dots against their due weeks), as two tabs, so that I see both today's work and the year.
65. As a VA POC, I want to endorse a VA Submission that is Awaiting Endorsement, so that the System's VA for this Cycle is complete.
66. As a VA POC, I want to return a submission to the SMO/Tech Lead with comments, so that I can challenge something the Cyber Manager confirmed.
67. As a VA POC, I want to endorse after week 13, with the submission marked Endorsed Late, so that late work still completes and the lateness stays visible.
68. As a VA POC, I want any VA Submission that isn't Endorsed or Exempted when the VA Cycle closes (4 weeks after Q4 ends, or earlier if I close it) to become Not Endorsed, so that each Cycle's report is final.
69. As one of the two VA POCs, I want items the other VA POC has handled to clear from my list with "handled by (name)", so that we don't duplicate work.
70. As a VA POC, I want to be blocked from endorsing a VA Submission I acted on at another step, for example as its SMO, so that the other VA POC endorses it.
71. As a VA POC, I want to see Systems in the Quarter that have no VA Submission yet as Not Started, and Overdue after week 2, so that I can chase SMOs who haven't begun.

### Roles and access

72. As any user, I want to sign in through the organisation's single sign-on, with MFA at every sign-in, so that I don't manage another password and a stolen password alone can't get in (assumption A-4).
73. As a VA POC, I want the Cyber Manager role to come from the Cyber Manager column of the System Register, scoped to the Systems assigned to each person, so that reviewer access follows the plan.
74. As a VA POC, I want to grant an auditor a time-limited, read-only Auditor role, so that evidence is viewed in the app rather than emailed around.
75. As a system administrator, I want to set up the two VA POCs at deployment, and change them only through a change request, so that the highest-privilege role is controlled.
76. As any user, I want to be unable to grant a role to myself, so that privileges can't be escalated.
77. As any user, I want sensitive actions (endorsing, approving an Exemption, uploading the Register, changing roles, approving the disposal list, bulk or detailed export) to require a fresh MFA sign-in, so that a hijacked session can't do damage (assumption A-5).
78. As an SMO/Tech Lead, I want to see only my own Systems' reports and Findings, so that sensitive data is shared only with those who need it.
79. As a former SMO/Tech Lead, I want my access to end as soon as the Register names a new SMO, so that leavers keep no access.
80a. As a VA POC, I want any role unused for 90 days to lapse automatically and come back only when a Register upload names that person again, and anyone no longer named, or disabled at the IdP, to lose access at once, so that no dormant access remains while annual SMOs aren't locked out.
80. As a new SMO/Tech Lead, I want the Draft handed to me with the previous SMO's proposals marked "proposed by (previous SMO)", so that I can re-confirm them rather than start over.

### Exports and audit

81. As a VA POC, I want a management summary export (counts by Quarter and state, Endorsed Late, and a by-System table with SMO, state, Overdue and the number of open Deviations) in Excel or PDF, with no vulnerability details, so that it's safe to circulate.
82. As an Auditor, I want an evidence pack for the Quarters and Systems I choose (Findings, Finding Statuses, who proposed and who confirmed each, and the audit trail), mainly in Excel with PDF also available, so that I can check the process.
83. As an Auditor, I want to browse the same evidence read-only in the app, so that I don't need copies.
84. As a VA POC or Auditor, I want an append-only audit log of every view, download, upload, status change, confirmation, Dispute, return, endorsement, Exemption decision, Register change, role change and export, so that every action is accountable.
85. As a Cyber Manager, I want to see the audit history of the VA Submissions I reviewed, so that I can trace what happened to them.
86. As anyone receiving an export, I want it watermarked with its classification (Confidential when it covers the whole set), so that its handling rules are obvious.
87. As a VA POC, I want to approve a list of records due for disposal after their retention period, with a fresh MFA sign-in, so that data is deleted on time, with approval, and never by a hijacked session.

### Notifications

88. As an SMO/Tech Lead, I want reminders 5 days before and on the end of week 2, then weekly while Overdue with the Cyber Manager copied in, so that I don't miss my window.
89. As the Cyber Manager assigned to a System, I want the same pattern of reminders against the end of week 4, with the VA POCs copied in, and to be the Cyber Manager copied in on that System's SMO reminders, so that reviews don't stall.
90. As a VA POC, I want reminders 2 weeks and 1 week before the end of week 13, so that endorsements finish within the Quarter.
91. As any user, I want emails to carry only the System, state, action needed, due date and a link, never Finding details, so that a forwarded email leaks nothing.
92. As any user, I want an in-app list of all my notifications, so that I can look back at them.
93. As any user, I want FYI events (Endorsed, Carried Over confirmed) in an optional weekly digest, while deadline, Overdue, rework, Disputed and Register-change messages always come, so that I get the essentials without noise.
94. As an SMO/Tech Lead, I want to be told when my System is moved to another Quarter, transferred to or from me, or withdrawn, so that I'm not surprised.

## Implementation Decisions

### Modules

These are deep modules: a small interface in front of a lot of behaviour. Each is a Spring Boot back-end module exposed through one REST API that the React app uses.

- **System Register:** parses the fixed Excel template (System ID, System Name, SMO/Tech Lead email, Cyber Manager email, Quarter); computes a **Register change preview** by matching on System ID (add, update, retire, move Quarter, change SMO) and classifies each effect on in-flight VA Submissions as *Will withdraw*, *Will move*, *Will transfer* or *Blocked*; applies a confirmed preview. Holds the Unlinked SMO and SMO Unreachable flags. Blocks rows with no Cyber Manager, or where the Cyber Manager is the System's SMO. A Cyber Manager change is a transfer, like an SMO change. The Register is the source of the Cyber Manager role.
- **Submission Workflow:** owns the VA Submission state machine, Quarter windows, Overdue, Endorsed Late, the Cycle close, Exemptions, and the **segregation-of-duties guard**. Its interface is one command per transition (start, submit, confirm or Dispute Findings, pass to endorsement, return, endorse, request, approve or reject Exemption, withdraw, close Cycle), and each is rejected when the state, role or segregation-of-duties rule doesn't allow it.
- **Report Ingestion:** the upload pipeline: quarantine store → malware scan → type allowlist → schema check → parse → pre-fill scan date and target → age check. There are **four parser adapters** behind one interface ("file in, Findings plus metadata out"): Dependency-Check (XML/JSON), SCAP ARF/XCCDF, Nessus `.nessus`, and Burp XML. Keep them separate, because the vendor formats change independently.
- **Finding Ledger:** Finding identity (below), Finding Statuses and their required fields, matching on re-upload, **carry-over** from the System's most recent Endorsed VA Submission, Target Date Missed, Risk Accepted expiry, Bulk-set batches, Manually Entered Findings, and the cycles-open count.
- **Access:** sign-in (OIDC), linking a user by email on first sign-in and then by `afcas.uuid`, the role store (roles are always held in the app, never taken from token claims), step-up MFA, per-System ownership checks on every request, including file downloads.
- **Dashboards (read models):** per-role views: the VA POC board plus cycle matrix plus Exemption requests waiting; the Cyber Manager inbox plus board; the SMO inbox.
- **Exports:** the management summary, the Cyber Manager detailed export and the auditor pack, in Excel and PDF, watermarked and logged.
- **Notifications:** the reminder schedule, event messages, content rules, the in-app list, the weekly digest, clearing handled items across both VA POCs, and bounce handling.
- **Audit Log:** append-only. Entries refer to Findings by ID and never copy vulnerability detail.
- **Retention:** calculates what's due (Cycle plus 3 years; superseded uploads after endorsement), produces the disposal list for VA POC approval, hard-deletes, and logs it.

**Adapters at the boundary.** These are the only things that are faked in tests:
- the identity provider (MCC SSO, OIDC)
- the malware scanner
- the mail relay
- the file store (encrypted object store)
- the clock

### VA Submission states (from *VA Submission workflow* and *Mid-cycle System Register changes*)

| State | Meaning | Next states |
|---|---|---|
| *(no VA Submission)* | Shown as **Not Started** for a System in the Quarter | Draft (the SMO starts one) |
| **Draft** | The SMO/Tech Lead is uploading reports and proposing statuses | In Review |
| **In Review** | With the Cyber Manager | Returned for Rework, Awaiting Endorsement |
| **Returned for Rework** | Back with the SMO, because of Disputed Findings or a VA POC return | In Review |
| **Awaiting Endorsement** | Every Finding is confirmed; with the VA POCs | Endorsed, Returned for Rework |
| **Endorsed** | Final. Flagged **Endorsed Late** if after week 13 | (none) |
| **Exemption Requested** | Frozen, entered from any state before Endorsed | Exempted, or back to the previous state |
| **Exempted** | Final; the work is kept for audit | If a conditional Exemption's future VA doesn't happen: back to the previous state (or Not Started), with new 2-week windows from the date it's flagged |
| **Withdrawn** | Final: the System was retired before endorsement. Read-only | (none) |
| **Not Endorsed** | Final, set when the VA Cycle closes | (none) |

**Overdue** is a flag, not a state:
- set on Not Started, Draft, In Review and Returned for Rework once their window has passed (end of week 2 for submitting, end of week 4 for review)
- set on Awaiting Endorsement after week 13

The windows are **soft**: nothing locks when one is missed.

### Timeline

A **VA Cycle** runs February to January and is named after the year it starts (VA Cycle 2026 runs February 2026 to January 2027). It has four Quarters of 13 weeks, each starting on the **first Monday** of February, May, August and November. The Cycle **closes 4 weeks after Q4 ends**, or earlier if a VA POC closes it; while two Cycles overlap, the dashboards show a Cycle selector.

A Quarter is 13 weeks:
- **Weeks 1–2: Submission Window.**
- **Weeks 3–4: Review Window.**
- **Weeks 5–13: Endorsement Window.**

A System added partway through a Quarter gets its Submission and Review windows from the date it was added.

### Finding identity and statuses (from *Where Findings come from*, *Finding Status lifecycle* and *Carrying Findings over between cycles*)

- **Identity, scoped to the System:**
  - Dependency-Check: CVE (or GHSA) plus package.
  - Secure Configuration: rule id or check id.
  - Burp: issue type plus host plus path.

  The same key is used to match re-uploads and to carry Findings over between cycles.
- **Finding Statuses and the fields each requires:**
  - Remediated: evidence.
  - False Positive: justification.
  - Risk Accepted: reason, plus the approver's name and role as free text (the risk owner is usually outside the app) and an optional approval memo. The Cyber Manager still confirms it. It expires at the end of each VA Cycle.
  - Remediation Planned: plan and target date.
- **The SMO proposes and the Cyber Manager confirms or Disputes.** A Deviation is a Finding that is Risk Accepted or Remediation Planned.
- **Every Finding at every severity needs a status.** There's no severity threshold.
- **Carry-over** comes from the System's most recent Endorsed VA Submission. From a Not Endorsed one, only the statuses the Cyber Manager had confirmed carry over. An earlier Deviation that's missing from the new scan becomes Remediated, and the Cyber Manager confirms it.

### Upload rules

- **Blocked:** malware, a disallowed type, an unreadable or schema-invalid file, or a scan dated in the future. At submission, **any file** more than 90 days old blocks submitting, and the message names that file.
- **Flagged, not blocked:** anything else, for example the SMO changing the target from what the file says.
- **Scan timeouts** are retried twice, then the upload is rejected.
- **Files can't be viewed** until they've passed the malware scan.

### Segregation of duties (from *Data handling* and *Confirm staff sign-in*)

A person may hold more than one role, but **may not act at more than one step on the same VA Submission**:
- submitting (SMO/Tech Lead)
- reviewing (Cyber Manager)
- endorsing or deciding an Exemption (VA POC)

There are **two VA POCs and no delegation**, so a VA POC who is conflicted leaves the item to the other one. Register changes that would break the rule (for example, making a submission's Cyber Manager its new SMO) are blocked in the preview.

### Roles and permissions

| Action | SMO/Tech Lead | Cyber Manager | VA POC (×2) | Auditor |
|---|---|---|---|---|
| Upload the System Register (which assigns SMOs and Cyber Managers), grant the Auditor role | | | ✓ (step-up) | |
| Start, prepare and submit a VA Submission; request an Exemption | own Systems | | | |
| Confirm or Dispute Findings; return; pass to endorsement | | ✓ (Systems assigned to it; not where it acted at another step) | | |
| Endorse; return; decide an Exemption | | | ✓ (step-up; not where it acted at another step) | |
| View reports and Findings | own Systems | Systems it reviews | all | chosen scope, read-only, time-limited |
| Exports | none | detailed (step-up) | management summary, auditor pack (step-up) | auditor pack |
| Read the audit log | | submissions it reviewed | all | all within scope |
| Approve disposal | | | ✓ (step-up) | |

### API contract (outline; the dev team fixes the details)

- **Resource-oriented REST over:**
  - VA Cycles and Quarters
  - Systems and the System Register (upload returns a preview id; applying the preview is a separate call)
  - VA Submissions (transitions are explicit action endpoints, not generic updates)
  - Scan Report files (upload is multipart; download is `Content-Disposition: attachment`)
  - Findings (read, plus propose, confirm and Dispute, including bulk operations over a list of ids that return a batch id)
  - Exemptions
  - Exports (generated asynchronously, then downloaded)
  - Notifications
  - the audit log (read only)
- **Errors** never echo parsed report content or stack traces.
- **Report content** is always returned as data, never HTML. The UI encodes it and never renders it raw.

### Screens (from the two prototypes)

- **Dashboards:** the VA POC gets tabs for the Quarter board and the Cycle matrix, plus Exemption requests waiting. The Cyber Manager gets tabs for the action inbox and the Quarter board. The SMO gets the action inbox for their own Systems. The prototype is [PROTOTYPE-dashboard-and-export.html](../../prototypes/PROTOTYPE-dashboard-and-export.html).
- **SMO submission:**
  - The first submission uses the spreadsheet with filter chips and the bulk bar.
  - Rework uses the guided queue.
  - Both show the Scan Report cards (check results, scan date, confirmed target, Replace), the progress bar and the Declaration.

  The prototype is [PROTOTYPE-smo-submission-screen.html](../../prototypes/PROTOTYPE-smo-submission-screen.html).
- **Classification banner** on every screen, and a classification label next to every input field (IM8 dp-8).

### Data handling (from *Data handling*)

- **Classification:** Restricted / Sensitive-Normal for a single report, and Confidential for the complete set (assumption A-1).
- **Retention:** VA Submissions, Scan Reports, Findings, Declarations and the audit log are kept for the **VA Cycle plus 3 years**. Superseded uploads are purged once the submission is Endorsed; the final Finding Status history is kept.
- **Disposal:** hard deletion, done automatically from a disposal list the VA POC approves, and logged. Soft-delete is allowed only before retention ends.
- **Encryption at rest is mandatory** for the database and the file store, with keys in a key-management service (KMS). TLS is used everywhere, plus HSTS, and secrets are kept in a secrets manager.
- **No emergency admin account:** all access goes through SSO.
- **Keys:** rotated at least yearly by the KMS; usable only by the app's service identity (see *Security controls*).

### Security controls (IM8, from *IM8/ARC controls*)

| Controls | What they mean for this app (MUST) |
|---|---|
| ac-1, as-7 | Every endpoint is **denied by default** and opened only to the roles in the permission matrix, enforced at method level, with per-System ownership checks on every request, including file downloads |
| ac-2 | **MFA at every sign-in for every user**, from the IdP. **Step-up** (a fresh MFA sign-in) for endorsing, approving an Exemption, uploading the Register, changing roles, approving the disposal list, and any bulk or detailed export |
| ac-3 | A role unused for **90 days lapses automatically**; it comes back only when a Register upload names the person again. The sign-in itself isn't disabled by the app, so annual SMOs aren't locked out. Anyone no longer named in the Register, or disabled at the IdP, **loses access immediately** (stricter than the 5-day rule) |
| ac-4 | The VA POCs **review every role holder** (Cyber Managers, Auditors, VA POCs) against the Register baseline **at each Register upload and at least quarterly**; each review is logged |
| ac-7, ac-12 | Sign-in through the organisation's IdP, **MCC SSO**, taken to satisfy ac-7's "organisational IdP for internal users" (assumption A-4); no local credentials; no public users |
| ac-8 | Joiner/leaver lifecycle: just-in-time linking on first sign-in; roles from the Register; Auditor roles expire |
| as-1 | File types allowlisted and schema-checked; a **maximum size per file type** (values set by the dev team); every free-text input (justifications, plans, comments, Exemption evidence) validated for type, length and format |
| as-2 | Parameterised queries only |
| as-3, as-9 | Report content is output-encoded and never rendered raw; a **minimally permissive CSP**: `default-src 'self'`, no `unsafe-inline` or `unsafe-eval`, `frame-ancestors 'none'` |
| as-10 | HSTS with **`max-age` of at least 31536000 seconds** (1 year) |
| as-11 | Sessions end after **30 minutes idle**, and after **8 hours** at most, when the user must sign in again |
| as-12 | Uploads are malware-scanned before they are read |
| as-13 | No internal details or stack traces in errors |
| as-14 | Vetted crypto libraries only |
| ck-1 | Key establishment follows the chosen KMS (decided in implementation) |
| ck-2 | Keys are **rotated at least yearly**, automatically by the KMS |
| ck-4 | **Only the app's service identity can use the keys**; no person can export them or decrypt with them directly; key use is logged |
| dp-3 | **TLS 1.2 or higher** on every connection (browser, database, object store, malware scanner, mail relay, IdP); SSLv3, TLS 1.0 and 1.1 disabled; certificate and hostname validation never turned off |
| dp-8 | Classification labels next to every input field, and a banner on every screen |
| lm-4 | Audit logging of data access and management events, **plus sign-in, sign-out, failed sign-in or step-up, and every refused request** |
| lm-15 | One **JSON (ECS) schema** for application and audit logs: actor, role, action, System, VA Submission, outcome, timestamp and correlation ID |
| lm-16 | Key signals monitoring (metrics set with the non-functional targets) |
| lm-19 | No vulnerability detail in logs |
| pm-6 | System documentation, produced during implementation |

**ARC doesn't apply**, because the app has no AI features.

### Notifications (from *Notification channel*)

- **Channels:** email through the organisation's mail relay (assumption A-9), plus an in-app notification list. No Teams.
- **Content:** a message contains only the System, the state, the action needed, the due date and a link.
- **Required messages:** deadline reminders, Overdue, Return for Rework, Disputed and Register changes. These can't be switched off.
- **FYI events** go in an optional weekly digest.
- **Both VA POCs** receive VA POC items, which clear for one when the other acts.
- **A bounced email** sets SMO Unreachable.

### Technology

**Decided:** a React front end and a Spring Boot back end. Everything else (database, object store, scanner product, hosting) is for the dev team to decide, within the assumptions below.

## Testing Decisions

- **What makes a good test here:** it drives the app the way a user or the React client does, then checks the observable result: the HTTP response, the resulting VA Submission and Finding states, the audit entries, the captured notifications and the export content. It never reaches into internal classes or tables. A refactor that keeps the behaviour must not break any test.
- **Seam 1, the primary one, carries almost every test: the back end's HTTP API.**
  - It runs with a full Spring context and a real database (Testcontainers), and the whole workflow, the parsers, the upload checks, segregation of duties, carry-over, the Register preview and apply, and the audit log all run for real.
  - Only the boundary adapters are faked:
    - an OIDC stand-in that issues a token for any test user in any role, and can simulate step-up MFA
    - a malware scanner that returns clean, infected or timeout
    - a mail relay that captures messages and can simulate a bounce
    - an in-memory file store
    - a **controllable clock**, so tests can set "week N of Quarter Q" and exercise Overdue, Endorsed Late, the Cycle closing, Target Date Missed, Risk Accepted expiry, a System added partway through a Quarter, and reminders
  - **The report parsers are tested through the upload endpoint** with a set of real sample exports: Dependency-Check XML and JSON, CIS-CAT or OpenSCAP ARF/XCCDF, `.nessus`, Burp XML, plus malformed, disallowed-type, too-old and future-dated files. They have no separate seam.
- **Seam 2, thin and for acceptance only: the browser, through Playwright** (via `/e2e` later).
  - It covers a few end-to-end scenarios of the prototyped screens: the SMO spreadsheet with bulk-set, the rework queue, each role's dashboard tabs, and one export.
  - Business rules are never tested here.
- **No separate seam** for React components or domain classes.
- **Areas that must be covered through Seam 1:**
  - every row of the state table, including rejected transitions
  - the segregation-of-duties guard, including across the two VA POCs and on Register changes
  - every upload check, and the order they run in
  - matching on re-upload and across cycles
  - every Register-preview grouping
  - export content, watermarks and the absence of vulnerability details from the management summary and all notifications
  - audit entries for every action listed in the audit-log user story
  - access denials, including a direct file download by someone who isn't the owner
- **Prior art:** none; this is a new project. The house style is Spring Boot test slices plus Testcontainers, as the installed `spring-test-review` skill expects.

## Out of Scope

- Running or scheduling scans: the app only collects the reports.
- Tracking remediation work: only a Finding's status and plan are recorded, not the fix itself.
- VA of vendor or third-party systems.
- Penetration testing beyond the Burp Suite report.
- Pulling results from the Burp DAST API. For now, the app only accepts uploads.
- An Excel round-trip of Findings for the SMO (download, fill in offline, upload back).
- Escalating overdue submissions beyond the VA POCs.
- VA POC delegation, which isn't needed because there are two VA POCs.
- Any AI or LLM feature. Adding one would bring the ARC controls into scope.

## Releases

Decided by grilling the PM delivery plan on 2026-09-28. The plan is in the PM report (`reports/pm-report.html`). Its source is the stories file [spec.stories.yaml](spec.stories.yaml), which sets the release of every story. Story numbers below match the User Stories list.

### Release 1: run one Quarter end to end (81 stories)

Everything not listed under Release 2. That covers the System Register upload, preview and retirement; VA Submissions; Scan Report ingestion; Cyber Manager review; SMO rework; endorsement; Exemptions; roles and access, with sign-in and the role store first; the required notifications; the management summary export; and the audit log.

**Story 20 is split for delivery.** It stays as the **upload pipeline**: quarantine, malware scan, type and schema checks, and storage. It is built first, and **four parser stories** follow it. They're **independent of each other and built in parallel**, and all four are in Release 1, because every VA Submission needs a Secure Configuration report:

| Story | Parser |
|---|---|
| 20a | OWASP Dependency-Check, XML or JSON |
| 20b | SCAP ARF/XCCDF, from CIS-CAT Pro or OpenSCAP |
| 20c | Nessus `.nessus` compliance audit |
| 20d | Burp Suite XML issue export |

These four exist only in the stories file, as delivery units. Their behaviour is already described by stories 20–30 and the *Upload rules* above.

### Release 2: after the first live Quarter (17 stories)

None of these is needed to run a first Quarter:

| Area | Stories | Why they can wait |
|---|---|---|
| Carry-over between cycles | 31, 32, 33, 34, 53, 54 | Only apply once a System has an Endorsed VA Submission from an earlier Cycle |
| Mid-cycle System Register changes | 8, 9, 10, 11, 15, 80 | Can be handled by hand at first, by re-planning the next Register upload |
| Auditor pack and Auditor role | 74, 82, 83 | Audit evidence can come from the audit log and the management summary until then |
| Weekly FYI digest | 93 | Required notifications are in Release 1 (stories 88–92, 94) |
| Cyber Manager detailed export | 59 | Cyber Managers see full Finding detail in the app |

### Timeline and team

- **Go-live: Monday 2 August 2027,** at the start of Q3 of VA Cycle 2027. Q1 and Q2 of Cycle 2027 run the existing manual process.
- **Before go-live, working back from that date:**
  - UAT with real SMOs, a Cyber Manager and a VA POC: 2 weeks.
  - Security testing and fixes: 3 weeks. This covers a **vendor** penetration test (**book it in March 2027**), the threat model, and the `/pre-prod-check` gates.
  - Deployment and cutover: 1 week, ending 30 July 2027.
- **Release 1 build finished by about 18 June 2027.**
- **Team: 2 developers.**
  - Release 1 takes about **159 working days** at the Likely estimate (138 simulated, 180 pessimistic).
  - About 180 working days are available up to 18 June 2027 if the build started on 29 September 2026. So the build has to **start by early November 2026**; every week of delay before that uses up the buffer.
  - A third developer adds no time saving.
  - The estimates include coding, unit and integration tests, code review and documentation. They exclude the milestones above.
- **Pilot:** Q3 of Cycle 2027 is run with 2 weeks of close support from go-live, and a documented fallback to the manual process for any System that gets stuck.
- **Critical-path risk: story 50** (the Cyber Manager confirms or Disputes Findings). It carries the whole review engine: 24 tasks, about 60 days. Splitting it, for example into segregation of duties, bulk review, and re-review of what changed, is the main way to win back buffer.

## Further Notes

### Assumptions to confirm

The PO couldn't confirm these. They're tracked as a checklist in [Confirm policy facts with the cyber team](issues/11-confirm-policy-facts.md). Confirm them before `/spec-compliance`; if one changes, update the requirements that cite it.

| ID | Assumption | Source ticket |
|---|---|---|
| A-1 | Classification is Restricted / Sensitive-Normal for a single report, and Confidential for the complete set. | [Data handling](issues/07-data-handling.md) |
| A-2 | Hosting is a government cloud tier approved for that classification, never reachable from the internet, and accessed only through the organisation's network or a zero-trust gateway. | [Data handling](issues/07-data-handling.md) |
| A-3 | An approved malware-scanning service exists; the product isn't known yet. | [Data handling](issues/07-data-handling.md) |
| A-4 | All users sign in through MCC SSO (OIDC with PKCE and `private_key_jwt`, onboarded through MTM), with MFA at every sign-in, and MCC SSO counts as the organisational IdP for IM8 ac-7 (which names WOG AAD). The staff `kc_idp_hint` is still unknown. | [Confirm staff sign-in](issues/10-confirm-staff-sign-in.md) |
| A-5 | MCC SSO supports step-up MFA on demand; otherwise the app forces a full re-login. | [Confirm staff sign-in](issues/10-confirm-staff-sign-in.md) |
| A-6 | Role administration as described: SMOs and Cyber Managers come from the System Register, the VA POCs grant Auditors, and the VA POCs are set up by a system administrator. | [Confirm staff sign-in](issues/10-confirm-staff-sign-in.md) |
| A-7 | It's acceptable to link sign-ins by email on first login and use `afcas.uuid` afterwards. | [Confirm staff sign-in](issues/10-confirm-staff-sign-in.md) |
| A-8 | The IM8 areas the policy catalog doesn't cover (classification, retention, encryption at rest, hosting) hold when checked against the full IM8 policy or the agency ISM. | [IM8/ARC controls](issues/06-im8-arc-controls.md) |
| A-9 | The organisation's mail relay is approved for these notification emails. | [Notification channel](issues/14-notification-channel.md) |

### Open questions for the dev team (not decided during wayfinding)

- Non-functional targets: availability, performance, backup and disaster recovery, browser support, accessibility level, environments. The data volume is small: about 48 Systems a year and about 200 Findings per submission.
- Database, object store, KMS and scanner products, and the hosting platform (within A-2 and A-3).
- Whether MPDS attributes are used at all, given that roles are held in the app.
- Size limits per file type (IM8 as-1 needs them).
- The exact wording of the Declaration text, and who owns its versions.

### Decision record

Every decision above traces to a resolved ticket on the map. See *Decisions so far* in [Annual VA: map](map.md): 13 resolved tickets, plus the research files for scan report formats, identity provider and IM8/ARC controls. Two later changes supersede earlier answers:
- **Two VA POCs with no delegation** replaces "one VA POC who can delegate".
- **The Cyber Manager detailed export** was added after *Data handling*.
- **`/grill-me` on this spec (2026-09-28)** added: a Cyber Manager per System in the Register (replacing a VA POC-maintained list), the free-text Risk Accepted approver, the February-January calendar and Cycle close, the failed conditional Exemption rule, and the per-file 90-day rule.
- **`/spec-compliance` (2026-09-29)** resolved 3 FAILs and 11 AUTO-FIX items: MFA at every sign-in and step-up for approving disposal (ac-2); inactive roles lapse and return via the Register, with immediate removal for anyone no longer named (ac-3, story 80a); MCC SSO as the organisational IdP (ac-7, A-4); and tightened MUST wording in *Security controls* for as-1, as-9, as-10, as-11, ac-1, ac-4, dp-3, lm-4, lm-15, ck-2 and ck-4. Reports: `artifacts/spec-compliance/`.
- **Grilling the PM delivery plan (2026-09-28)** added the *Releases* section: two releases, story 20 split into a pipeline story plus parser stories 20a–20d, the go-live date, the pre-go-live milestones and the 2-developer plan.

All of these are reflected here.

- **Grilling the Release 1 tickets (2026-09-29)** added decisions that are recorded in the [Release 1 tickets](../annual-va-release-1/issues/) but not yet in the sections above:
  - OWASP Dependency and Burp Suite can each be marked **not applicable** for a VA Submission, with a justification the Cyber Manager confirms or Disputes like a Finding. Secure Configuration is always required.
  - The System Register is **one standing list**: its Quarter column applies to every VA Cycle, a new Cycle opens automatically, and while two Cycles overlap a re-upload changes only the newer one.
  - A one-off **first live Quarter** setting: VA Cycle 2027 goes live at Q3, and earlier Quarters show as *Handled outside the app* (no Overdue, no reminders, never Not Endorsed).
  - For a **conditional Exemption**, the SMO records that the future VA happened and a VA POC confirms it; it doesn't lapse while that confirmation is pending.
  - Returned for Rework is Overdue after week 4, even after a VA POC return; an SMO may start a VA Submission before their Quarter; an Exemption on a Not Started System creates the VA Submission.

  The first and third change what the approved spec requires, so re-run `/spec-compliance` on them if the cyber team wants them checked.
- **`/issue-verification` of the Release 1 tickets (2026-09-29)** found and resolved these, also recorded in the tickets and in [CONTEXT.md](../../CONTEXT.md):
  - **Quarter length.** First Mondays don't give four 13-week Quarters every year (VA Cycle 2027's Q4 is 14 weeks; 2028's Q1 is 12 and Q2 14). A Quarter now runs from its first Monday to the day before the next Quarter starts, and every "week 13" rule means the Quarter's last day. This corrects *Timeline* and the VA Submission states table.
  - **Occurrences.** Findings with the same key in one VA Submission (one rule failing on two hosts, one CVE and package in two components) are one Finding with an occurrence per host or file. Replacing a file compares only that file's occurrences.
  - **Checks that didn't complete** (XCCDF `error` and `unknown`, Nessus `ERROR`) become Findings; non-compliance Nessus ReportItems are ignored. This extends story 29.
  - **Role lapse (ac-3).** With a standing Register no annual upload re-names people, so a lapsed SMO/Tech Lead or Cyber Manager role also returns automatically when the Quarter of a System they're still named for begins, or when work is handed to that person (a Return for Rework, new windows after a conditional Exemption lapse, a transfer); VA POC roles return only through configuration. Automatic returns are listed on the VA POCs' role-holder review before each Quarter. The review (ac-4) now covers SMO/Tech Leads too, at least every 3 months, and its only change is suspending a person's role for a System (with step-up) until a Register upload names someone. **Re-run `/spec-compliance` on ac-3 and ac-4.**
  - **Superseded files** go on the approved disposal list once their submission is Endorsed, instead of being purged automatically; every other removal before retention ends is a soft-delete.
  - **Q4 conditional Exemptions** always resolve after the Cycle has closed (3 months after Q4's last day), so a Q4 System whose scheduled VA doesn't happen goes straight to Not Endorsed, with no new windows. This is the only change allowed to a closed Cycle.
  - **Step-up** follows the permissions matrix: endorse, return, and every Exemption decision (approve, reject, confirm a conditional Exemption's VA), plus closing a Cycle early.
  - A submission still Exemption Requested when the Cycle closes becomes Not Endorsed; a conditional Exemption lapses if its VA isn't recorded within 3 months after the Quarter's last day, and a lapse after the Cycle's close makes it Not Endorsed.
  - A VA POC's own Register upload may name them as an SMO/Tech Lead or Cyber Manager (the reading of story 76 chosen on 2026-09-29); the segregation-of-duties guard controls the steps.
  - A Cyber Manager reads the audit history only of submissions they reviewed on Systems still assigned to them.
  - Parser sample exports come from throwaway test targets, never a real System, because a real report is a live target list.

### Next steps

1. Run `/grill-me` on this spec (done 2026-09-28).
2. Commit and hand off (done; the repository is `asheewee1771/annualVA`).
3. Plan delivery with `/dependency-orchestrator`, then `/grill-me` (done 2026-09-28; see *Releases*).
4. `/spec-compliance` (done 2026-09-29; Gate 1 approved by the PO on 2026-09-29).
5. The dev team then runs `/to-tickets` and `/issue-verification`, in Release 1 order.
