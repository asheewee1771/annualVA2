# Annual VA

Runs the organisation's yearly vulnerability assessment: every in-scope System submits scan evidence and a declaration once a year, in its assigned Quarter, for review and endorsement.

## Language

### Cycle and scheduling

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

### Submission

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

**Confidence**:
How sure Burp Suite says it is that an issue is real: Certain, Firm or Tentative. A Finding's confidence is the most sure of its occurrences; a Tentative Finding is one the SMO/Tech Lead may mark False Positive with a reason. Only Burp rates its issues this way.

**Export time**:
The only date a Burp Suite export records: when the file was exported, not when the scan was made. It is offered as the scan date, flagged, for the SMO/Tech Lead to correct.

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

### Submission lifecycle

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

### Roles and flow

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
