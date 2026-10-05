# Annual VA: Product Requirements (PRD)

**Status:** derived on 2026-10-05 from the Gate-1-approved early-draft spec (TRD) of 2026-09-28, approved 2026-09-29 by the PO. The wording of every requirement below is the spec's own. The spec (`.scratch/annual-va/spec.md`) remains the authoritative document; where this PRD and the spec differ, the spec wins. Technical design lives in the TRD half of the spec and in `docs/implementation-plan.md`.

**Scope of this document:** the problem, the solution in the users' terms, the user stories by actor, the product decisions taken after the stories were written, what is out of scope, the two releases and the timeline, the assumptions still to be confirmed, and the open questions. The glossary every term follows is attached.

## 1. Problem

Once a year, every in-scope System in the organisation (about 40–48 of them) must prove it has no outstanding vulnerabilities or configuration deviations. The work is spread over four Quarters of about 10–12 Systems each. For each System, the SMO/Tech Lead has to supply three Scan Reports (OWASP Dependency-Check, Secure Configuration and Burp Suite) and a Declaration. A Cyber Manager then reviews it, and a VA POC endorses it.

Today this happens outside any system. The VA POC chases SMOs by hand. Nobody can easily see which Systems in a Quarter have submitted, are stuck in review, or are overdue. Cyber Managers can't tell whether every individual Finding has been dealt with. Findings that were accepted or deferred last year aren't reliably followed up this year. And the scan reports themselves are a sensitive target list of real, unfixed weaknesses, yet they circulate as loose files.

## 2. Solution

An internal web app (React front end, Spring Boot back end) that runs the whole VA Cycle.

**Planning.** The VA POC loads the **System Register** from a fixed Excel template, which assigns each System to a **Quarter**. The app then tracks every System through its 13-week Quarter.

**Submitting.** The SMO/Tech Lead starts a **VA Submission** and uploads the three Scan Reports in machine-readable form. The app malware-scans the files, reads every **Finding** out of them, and pre-fills statuses carried over from the System's last Endorsed submission. The SMO proposes a **Finding Status** for every Finding, individually or in bulk, then ticks the Declaration and submits.

**Reviewing and endorsing.** The Cyber Manager confirms or **Disputes** each Finding and can return the submission for rework. Once every Finding is confirmed, one of the two VA POCs endorses it. Systems that had, or will soon have, a separate VA can be **Exempted**.

**Keeping people informed.** Deadlines are soft. Missing one sets **Overdue** and triggers reminders by email and in-app notification. Each role gets a dashboard for its own work. Management gets a detail-free summary export, Cyber Managers a detailed export, and auditors an evidence pack plus a read-only Auditor role.

**Protecting the data.** Everything is encrypted at rest, kept in an append-only audit log, and retained for the VA Cycle plus 3 years. No person may act at more than one step on the same VA Submission.

## 3. Users and roles

| Role | Who | What they do in the app |
|---|---|---|
| SMO/Tech Lead | The person accountable for a System, named in the System Register | Starts the VA Submission, uploads the three Scan Reports, proposes a Finding Status for every Finding, ticks the Declaration, submits, reworks what is Disputed, requests Exemptions |
| Cyber Manager | The reviewer assigned to a System in the System Register | Confirms or Disputes each Finding Status, returns for rework, passes to endorsement, exports detail for the Systems they review |
| VA POC (two, no delegation) | The organisation-wide endorsers | Load the System Register, endorse, return, decide Exemptions, close the Cycle, review role holders, approve disposal, export the management summary |
| Auditor | A time-limited, read-only role | Browses chosen Quarters and Systems and takes the evidence pack |
| System administrator | Deployment | Sets up the two VA POCs; changes them only through a change request |

## 4. User stories

#### VA Cycle and System Register

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

#### Starting and preparing a VA Submission

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

#### Exemptions

43. As an SMO/Tech Lead, I want to request an Exemption at any time before endorsement, with evidence of a VA done within the 3 months before the Quarter or one scheduled within the 3 months after, so that my System isn't assessed twice.
44. As an SMO/Tech Lead, I want my submission frozen while the Exemption request is pending, and returned to exactly its previous state if the request is rejected, so that I don't lose work.
45. As a VA POC, I want a list of Exemption requests waiting on my dashboard, so that I can decide them promptly.
46. As a VA POC, I want to approve or reject an Exemption, with approval making the submission Exempted while keeping its work for audit, so that the outcome is clear and traceable.
47. As a VA POC, I want an Exemption based on a future VA to stay conditional, and, if that VA doesn't happen within 3 months, the System to be flagged and returned to its previous state (or Not Started) with new 2-week windows from the date it's flagged and endorsement due by the later of its Quarter's end and the Cycle's close, so that Exemptions can't be used to dodge the VA.

#### Review by the Cyber Manager

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

#### Rework by the SMO/Tech Lead

60. As an SMO/Tech Lead whose submission was Returned for Rework, I want a guided queue that takes me one Finding at a time (Disputed, then Target Date Missed, then Carried Over to re-confirm, then new Critical/High, then new Medium/Low), so that I fix exactly what was raised.
61. As an SMO/Tech Lead, I want to see the Cyber Manager's Dispute comment on each Finding, so that I know what to change.
62. As an SMO/Tech Lead, I want keyboard shortcuts in the rework queue (1–4 picks a status, Enter saves and moves on), so that rework is fast.
63. As an SMO/Tech Lead, I want a return from the VA POC to come back to me, and my resubmission to go through the Cyber Manager again, so that the chain of confirmation stays whole.

#### Endorsement by a VA POC

64. As a VA POC, I want a board for the current Quarter and a matrix of the whole VA Cycle (a strip showing all four Quarters, then one row per System with its milestone dots against their due weeks), as two tabs, so that I see both today's work and the year.
65. As a VA POC, I want to endorse a VA Submission that is Awaiting Endorsement, so that the System's VA for this Cycle is complete.
66. As a VA POC, I want to return a submission to the SMO/Tech Lead with comments, so that I can challenge something the Cyber Manager confirmed.
67. As a VA POC, I want to endorse after week 13, with the submission marked Endorsed Late, so that late work still completes and the lateness stays visible.
68. As a VA POC, I want any VA Submission that isn't Endorsed or Exempted when the VA Cycle closes (4 weeks after Q4 ends, or earlier if I close it) to become Not Endorsed, so that each Cycle's report is final.
69. As one of the two VA POCs, I want items the other VA POC has handled to clear from my list with "handled by (name)", so that we don't duplicate work.
70. As a VA POC, I want to be blocked from endorsing a VA Submission I acted on at another step, for example as its SMO, so that the other VA POC endorses it.
71. As a VA POC, I want to see Systems in the Quarter that have no VA Submission yet as Not Started, and Overdue after week 2, so that I can chase SMOs who haven't begun.

#### Roles and access

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

#### Exports and audit

81. As a VA POC, I want a management summary export (counts by Quarter and state, Endorsed Late, and a by-System table with SMO, state, Overdue and the number of open Deviations) in Excel or PDF, with no vulnerability details, so that it's safe to circulate.
82. As an Auditor, I want an evidence pack for the Quarters and Systems I choose (Findings, Finding Statuses, who proposed and who confirmed each, and the audit trail), mainly in Excel with PDF also available, so that I can check the process.
83. As an Auditor, I want to browse the same evidence read-only in the app, so that I don't need copies.
84. As a VA POC or Auditor, I want an append-only audit log of every view, download, upload, status change, confirmation, Dispute, return, endorsement, Exemption decision, Register change, role change and export, so that every action is accountable.
85. As a Cyber Manager, I want to see the audit history of the VA Submissions I reviewed, so that I can trace what happened to them.
86. As anyone receiving an export, I want it watermarked with its classification (Confidential when it covers the whole set), so that its handling rules are obvious.
87. As a VA POC, I want to approve a list of records due for disposal after their retention period, with a fresh MFA sign-in, so that data is deleted on time, with approval, and never by a hijacked session.

#### Notifications

88. As an SMO/Tech Lead, I want reminders 5 days before and on the end of week 2, then weekly while Overdue with the Cyber Manager copied in, so that I don't miss my window.
89. As the Cyber Manager assigned to a System, I want the same pattern of reminders against the end of week 4, with the VA POCs copied in, and to be the Cyber Manager copied in on that System's SMO reminders, so that reviews don't stall.
90. As a VA POC, I want reminders 2 weeks and 1 week before the end of week 13, so that endorsements finish within the Quarter.
91. As any user, I want emails to carry only the System, state, action needed, due date and a link, never Finding details, so that a forwarded email leaks nothing.
92. As any user, I want an in-app list of all my notifications, so that I can look back at them.
93. As any user, I want FYI events (Endorsed, Carried Over confirmed) in an optional weekly digest, while deadline, Overdue, rework, Disputed and Register-change messages always come, so that I get the essentials without noise.
94. As an SMO/Tech Lead, I want to be told when my System is moved to another Quarter, transferred to or from me, or withdrawn, so that I'm not surprised.

## 5. Product decisions taken after the stories were written

These are recorded in the spec's decision record and in its Release 1 tickets; the first and third of the 2026-09-29 grilling items change what the approved spec requires.

Every decision above traces to a resolved ticket on the map. See *Decisions so far* in Annual VA: map: 13 resolved tickets, plus the research files for scan report formats, identity provider and IM8/ARC controls. Two later changes supersede earlier answers:
- **Two VA POCs with no delegation** replaces "one VA POC who can delegate".
- **The Cyber Manager detailed export** was added after *Data handling*.
- **`/grill-me` on this spec (2026-09-28)** added: a Cyber Manager per System in the Register (replacing a VA POC-maintained list), the free-text Risk Accepted approver, the February-January calendar and Cycle close, the failed conditional Exemption rule, and the per-file 90-day rule.
- **`/spec-compliance` (2026-09-29)** resolved 3 FAILs and 11 AUTO-FIX items: MFA at every sign-in and step-up for approving disposal (ac-2); inactive roles lapse and return via the Register, with immediate removal for anyone no longer named (ac-3, story 80a); MCC SSO as the organisational IdP (ac-7, A-4); and tightened MUST wording in *Security controls* for as-1, as-9, as-10, as-11, ac-1, ac-4, dp-3, lm-4, lm-15, ck-2 and ck-4. Reports: `artifacts/spec-compliance/`.
- **Grilling the PM delivery plan (2026-09-28)** added the *Releases* section: two releases, story 20 split into a pipeline story plus parser stories 20a–20d, the go-live date, the pre-go-live milestones and the 2-developer plan.

All of these are reflected here.

- **Grilling the Release 1 tickets (2026-09-29)** added decisions that are recorded in the Release 1 tickets but not yet in the sections above:
  - OWASP Dependency and Burp Suite can each be marked **not applicable** for a VA Submission, with a justification the Cyber Manager confirms or Disputes like a Finding. Secure Configuration is always required.
  - The System Register is **one standing list**: its Quarter column applies to every VA Cycle, a new Cycle opens automatically, and while two Cycles overlap a re-upload changes only the newer one.
  - A one-off **first live Quarter** setting: VA Cycle 2027 goes live at Q3, and earlier Quarters show as *Handled outside the app* (no Overdue, no reminders, never Not Endorsed).
  - For a **conditional Exemption**, the SMO records that the future VA happened and a VA POC confirms it; it doesn't lapse while that confirmation is pending.
  - Returned for Rework is Overdue after week 4, even after a VA POC return; an SMO may start a VA Submission before their Quarter; an Exemption on a Not Started System creates the VA Submission.

  The first and third change what the approved spec requires, so re-run `/spec-compliance` on them if the cyber team wants them checked.
- **`/issue-verification` of the Release 1 tickets (2026-09-29)** found and resolved these, also recorded in the tickets and in CONTEXT.md:
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

## 6. Out of scope

- Running or scheduling scans: the app only collects the reports.
- Tracking remediation work: only a Finding's status and plan are recorded, not the fix itself.
- VA of vendor or third-party systems.
- Penetration testing beyond the Burp Suite report.
- Pulling results from the Burp DAST API. For now, the app only accepts uploads.
- An Excel round-trip of Findings for the SMO (download, fill in offline, upload back).
- Escalating overdue submissions beyond the VA POCs.
- VA POC delegation, which isn't needed because there are two VA POCs.
- Any AI or LLM feature. Adding one would bring the ARC controls into scope.

## 7. Releases and timeline

Decided by grilling the PM delivery plan on 2026-09-28. The plan is in the PM report (`reports/pm-report.html`). Its source is the stories file spec.stories.yaml, which sets the release of every story. Story numbers below match the User Stories list.

#### Release 1: run one Quarter end to end (81 stories)

Everything not listed under Release 2. That covers the System Register upload, preview and retirement; VA Submissions; Scan Report ingestion; Cyber Manager review; SMO rework; endorsement; Exemptions; roles and access, with sign-in and the role store first; the required notifications; the management summary export; and the audit log.

**Story 20 is split for delivery.** It stays as the **upload pipeline**: quarantine, malware scan, type and schema checks, and storage. It is built first, and **four parser stories** follow it. They're **independent of each other and built in parallel**, and all four are in Release 1, because every VA Submission needs a Secure Configuration report:

| Story | Parser |
|---|---|
| 20a | OWASP Dependency-Check, XML or JSON |
| 20b | SCAP ARF/XCCDF, from CIS-CAT Pro or OpenSCAP |
| 20c | Nessus `.nessus` compliance audit |
| 20d | Burp Suite XML issue export |

These four exist only in the stories file, as delivery units. Their behaviour is already described by stories 20–30 and the *Upload rules* above.

#### Release 2: after the first live Quarter (17 stories)

None of these is needed to run a first Quarter:

| Area | Stories | Why they can wait |
|---|---|---|
| Carry-over between cycles | 31, 32, 33, 34, 53, 54 | Only apply once a System has an Endorsed VA Submission from an earlier Cycle |
| Mid-cycle System Register changes | 8, 9, 10, 11, 15, 80 | Can be handled by hand at first, by re-planning the next Register upload |
| Auditor pack and Auditor role | 74, 82, 83 | Audit evidence can come from the audit log and the management summary until then |
| Weekly FYI digest | 93 | Required notifications are in Release 1 (stories 88–92, 94) |
| Cyber Manager detailed export | 59 | Cyber Managers see full Finding detail in the app |

#### Timeline and team

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

## 8. Assumptions to confirm

The PO couldn't confirm these. They're tracked as a checklist in Confirm policy facts with the cyber team. Confirm them before `/spec-compliance`; if one changes, update the requirements that cite it.

| ID | Assumption | Source ticket |
|---|---|---|
| A-1 | Classification is Restricted / Sensitive-Normal for a single report, and Confidential for the complete set. | Data handling |
| A-2 | Hosting is a government cloud tier approved for that classification, never reachable from the internet, and accessed only through the organisation's network or a zero-trust gateway. | Data handling |
| A-3 | An approved malware-scanning service exists; the product isn't known yet. | Data handling |
| A-4 | All users sign in through MCC SSO (OIDC with PKCE and `private_key_jwt`, onboarded through MTM), with MFA at every sign-in, and MCC SSO counts as the organisational IdP for IM8 ac-7 (which names WOG AAD). The staff `kc_idp_hint` is still unknown. | Confirm staff sign-in |
| A-5 | MCC SSO supports step-up MFA on demand; otherwise the app forces a full re-login. | Confirm staff sign-in |
| A-6 | Role administration as described: SMOs and Cyber Managers come from the System Register, the VA POCs grant Auditors, and the VA POCs are set up by a system administrator. | Confirm staff sign-in |
| A-7 | It's acceptable to link sign-ins by email on first login and use `afcas.uuid` afterwards. | Confirm staff sign-in |
| A-8 | The IM8 areas the policy catalog doesn't cover (classification, retention, encryption at rest, hosting) hold when checked against the full IM8 policy or the agency ISM. | IM8/ARC controls |
| A-9 | The organisation's mail relay is approved for these notification emails. | Notification channel |

## 9. Open questions

(not decided during wayfinding)

- Non-functional targets: availability, performance, backup and disaster recovery, browser support, accessibility level, environments. The data volume is small: about 48 Systems a year and about 200 Findings per submission.
- Database, object store, KMS and scanner products, and the hosting platform (within A-2 and A-3).
- Whether MPDS attributes are used at all, given that roles are held in the app.
- Size limits per file type (IM8 as-1 needs them).
- The exact wording of the Declaration text, and who owns its versions.

## Appendix: glossary

Every term in this document follows the glossary at the repository root (`CONTEXT.md`). Use these terms; avoid the synonyms listed.

#### Cycle and scheduling

**VA Cycle**:
One year of vulnerability assessment, February to January, covering every in-scope System; named after the year it starts (VA Cycle 2027 runs February 2027 to January 2028). It closes 4 weeks after its Q4 ends, or earlier if a VA POC closes it.
_Avoid_: VA year, campaign, assessment year

**Quarter**:
One of the four periods of a VA Cycle, starting on the first Monday of February, May, August or November and running to the day before the next one starts (12 to 14 weeks, usually 13); each holds roughly 10 to 12 Systems.
_Avoid_: batch, wave, tranche

**System**:
One application with its own accountable SMO/Tech Lead and its own set of three Scan Reports, even if it has several components or hosts.
_Avoid_: app, asset, component

**System Register**:
The VA POC's standing list of every in-scope System with its SMO/Tech Lead, Cyber Manager and Quarter, loaded from a fixed Excel template; a re-upload updates, adds and retires Systems by System ID and never deletes one.
_Avoid_: CMDB, inventory, asset list

**Register change preview**:
What a System Register upload would change, shown before it is applied, with each effect on in-flight work grouped as Will withdraw, Will move, Will transfer or Blocked.

**Submission Window**:
Weeks 1 and 2 of a Quarter, when the SMO/Tech Lead submits the VA Submission. A System added partway through a Quarter gets its own two weeks from the day it was added.
_Avoid_: submission period

**Review Window**:
Weeks 3 and 4 of a Quarter, when the Cyber Manager reviews.
_Avoid_: review period

**Endorsement Window**:
Week 5 to the last day of a Quarter, when a VA POC endorses. Every rule that says "week 13" means the Quarter's last day.
_Avoid_: approval period

**First live Quarter**:
The Quarter of a VA Cycle from which that Cycle runs in this app; earlier Quarters are **Handled outside the app** and are left out of counts, reminders and the Cycle close.

**Exemption**:
Excuses a System from the current VA Cycle because it had a VA within the 3 months before its Quarter, or has one scheduled within the 3 months after. One based on a scheduled VA is a **conditional Exemption** until the SMO records that the VA happened and a VA POC confirms it.
_Avoid_: waiver, skip

#### Submission

**VA Submission**:
One System's evidence package for one VA Cycle: three Scan Reports and one Declaration.
_Avoid_: assessment, attestation, entry

**Scan Report**:
Evidence uploaded for one of three types: OWASP Dependency-Check, Secure Configuration (SCAP ARF/XCCDF or Nessus) or Burp Suite; a report may have several files. Secure Configuration is always required; the other two may be marked **not applicable** with a justification the Cyber Manager confirms.
_Avoid_: scan, result

**Supporting evidence**:
A PDF or HTML report attached for people to read; it is never parsed for Findings.

**Finding**:
One vulnerability or configuration deviation read from a Scan Report (or entered by hand), identified within a System by its identity key, which must have a Finding Status before the VA Submission can be submitted.
_Avoid_: issue, vuln, item

**Occurrence**:
One place a Finding was reported: a host, component or source file. One Finding may have several occurrences in one VA Submission.
_Avoid_: instance, duplicate

**Check that did not complete**:
A SCAP result of error or unknown, or a Nessus compliance result of ERROR; shown as a Finding because it is neither a pass nor a fail.
_Avoid_: failed check, skipped check

**Manually Entered Finding**:
A Finding the SMO/Tech Lead typed in because the tool cannot export a machine-readable report; it is marked for closer review.
_Avoid_: manual finding

**Finding Status**:
The disposition the SMO/Tech Lead proposes for a Finding and the Cyber Manager confirms or Disputes: Remediated, False Positive, Risk Accepted or Remediation Planned. Risk Accepted expires at the end of each VA Cycle.
_Avoid_: resolution, state

**Deviation**:
A Finding whose Finding Status is Risk Accepted or Remediation Planned: still open, but accounted for.
_Avoid_: exception, waiver

**Bulk-set**:
Finding Statuses applied to several Findings in one batch; the Cyber Manager can confirm or Dispute the batch as a whole.
_Avoid_: mass update

**Carried Over**:
A Finding whose Finding Status is pre-filled from the System's most recent Endorsed VA Submission and must be confirmed again.
_Avoid_: inherited, rolled over

**Target Date Missed**:
A Remediation Planned Finding still present after its target date; it needs a new date with a reason, or a different status.
_Avoid_: expired, overdue finding

**Disputed**:
A Finding whose proposed status the Cyber Manager does not accept, returned with a comment.
_Avoid_: rejected finding

**Declaration**:
The SMO/Tech Lead's acceptance of the fixed declaration text when submitting; the exact wording accepted is stored with the VA Submission.
_Avoid_: attestation, sign-off

#### Submission lifecycle

**Not Started**:
How a System in a Quarter is shown when it has no VA Submission yet.

**Draft**:
A VA Submission the SMO/Tech Lead has started but not submitted.

**In Review**:
A VA Submission with the Cyber Manager.

**Returned for Rework**:
A VA Submission sent back to the SMO/Tech Lead because of Disputed Findings or a VA POC return; confirmed Findings stay confirmed.

**Awaiting Endorsement**:
A VA Submission whose Findings are all confirmed, now with the VA POCs.

**Endorsed**:
A VA Submission a VA POC has accepted. **Endorsed Late** if that happened after the Quarter's last day.
_Avoid_: approved, closed

**Exemption Requested**:
A VA Submission frozen while a VA POC decides its Exemption; a rejection returns it to exactly its previous state.

**Exempted**:
The outcome for a System whose Exemption was approved; its work is kept for audit.

**Withdrawn**:
The outcome for a VA Submission whose System was retired before endorsement; read-only and excluded from counts and reminders.
_Avoid_: cancelled, deleted

**Not Endorsed**:
A VA Submission, or a System with none, that was neither Endorsed nor Exempted when its VA Cycle closed.

**Overdue**:
A flag, not a state, on a VA Submission or a Not Started System whose current window has passed; nothing locks.
_Avoid_: late, breached

#### Roles and flow

**SMO/Tech Lead**:
The person accountable for a System, named in the System Register, who prepares and submits its VA Submission.
_Avoid_: owner, submitter

**Cyber Manager**:
The reviewer assigned to a System in the System Register, who confirms or Disputes its Findings and returns or passes on its VA Submission.
_Avoid_: reviewer, approver

**VA POC**:
One of the two organisation-wide endorsers; either may act, there is no delegation, and one who acted at another step on a VA Submission leaves it to the other.
_Avoid_: approver, sign-off authority

**Auditor**:
A time-limited, read-only role over a chosen scope of Quarters and Systems.
_Avoid_: viewer

**Segregation of duties**:
The rule that no person acts at more than one step (submitting, reviewing, endorsing or deciding an Exemption) on the same VA Submission.

**Step-up**:
A fresh multi-factor sign-in required immediately before a sensitive action; it does not extend the session.
_Avoid_: re-authentication, MFA prompt

**Unlinked SMO**:
An SMO/Tech Lead email in the System Register that no sign-in has matched yet.

**SMO Unreachable**:
A System whose SMO/Tech Lead's notification emails fail to deliver, flagged for the VA POC to fix.

**Return for Rework**:
The act, by the Cyber Manager or a VA POC, of sending a VA Submission back to the SMO/Tech Lead with comments.
_Avoid_: reject, decline

**Role-holder review**:
The VA POCs' check of every role holder against the System Register, at each upload and at least quarterly, whose only change is suspending a person's role for a System.

