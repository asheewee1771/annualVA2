# A Cyber Manager reads the audit history of what they reviewed, through a port the VA Submissions answer

Status: accepted (items 2, 3 and 5 decided 2026-10-25, see the end; reads of the log are audited, built by ticket 50)

Ticket 48 (story 85) lets a Cyber Manager read the audit history of the VA Submissions they reviewed, on Systems still assigned to them (the permissions matrix: "Read the audit log: Cyber Manager, submissions it reviewed"; spec decision "A Cyber Manager reads the audit history only of submissions they reviewed on Systems still assigned to them"). It adds no migration and no endpoint: it widens `GET /api/audit-entries` (ADR 0014) and adds one tab to the Cyber Manager's screens.

1. **One endpoint, a read scope per caller.** `GET /api/audit-entries` now admits a VA POC or a Cyber Manager (an SMO/Tech Lead, an Auditor or a person with no role is still `403`, recorded as a refusal). Before it reads, the controller asks for the caller's `AuditReadScope`: every entry (a VA POC, also when they are a Cyber Manager too), or exactly the entries about a set of VA Submissions. The scope is one more narrowing of the one filtered query (`AuditFilter.within`, `submission_id = ANY(...)`), so the page, the total, the order and every other filter behave the same for both roles.

2. **What a Cyber Manager reviewed.** A VA Submission is in a Cyber Manager's scope when both are true:
   - they acted at the `REVIEW` step of it: `submission_participation` holds them at that step, which a confirm or Dispute of a Finding, of a not-applicable justification or of a batch records when it commits (ADR 0035 item 3, ADR 0036). Reading the VA Submission, or a refused review, does not count;
   - its System is still assigned to them: the access guard gives them the new need `Need.AUDIT` over it (a VA POC every System, a Cyber Manager the Systems the Register names them for, as `REVIEW`). A Register change that names another Cyber Manager, or retires the System, takes the history away at the next request (ADR 0011), and the new Cyber Manager reads it only once they review it.

   One statement answers it (`SubmissionStore.actedAt`, a join of the participation to the VA Submission's System).

3. **A VA Submission outside the scope is not found.** `submission=` naming a VA Submission outside the caller's scope answers `404 not-found`, the same as one that does not exist (ADR 0013), and like every 404 it is not audited (reviewer decisions, 2026-10-14). The other filters only narrow what the caller may read: `system=` naming another System is an empty page, not a 404, because a list filter reveals nothing the scope does not already allow. Without `submission=`, a Cyber Manager reads the history of every VA Submission they reviewed.

4. **The port keeps the audit log at the bottom.** `audit` depends on config and web only (ArchitectureTest), and who reviewed what lives in `submission`. So `audit` declares the port `AuditReaders` (`scopeOfCaller()`) and its answer `AuditReadScope`, and `submission` implements it (`AuditHistoryScope`, with the access guard and the participation). No module that writes to the log is reached from it. The Auditor's scope (Release 2) is the same port answered more widely.

5. **What the history holds.** Every entry about the VA Submission, whoever wrote it: the SMO/Tech Lead's start, uploads, proposals and submit, the reviews, reads by others, refusals and Overdue. An entry that names no VA Submission (a sign-in, a Register apply, an Overdue Not Started System) is never in a Cyber Manager's history. Entries hold ids and fixed codes only (ADR 0014), so the history carries no Finding detail by construction.

6. **The screen.** The Cyber Manager area gets a third tab, **Audit history** (`/cyber-manager/audit-history`), and the review screen links to the history of its own VA Submission (`/cyber-manager/submissions/{id}/audit-history`, the `submission` filter set and a link back; Clear returns to the filters the screen opened with). Both are the VA POC's audit log viewer (`AuditLogScreen`) with its own heading and the same filters, paging and columns; a Finding is an id. The action filter now names every action the log records, not only the first eleven, so a reader can pick `FINDING_REVIEWED` or `SUBMISSION_SUBMITTED`. A 404 is shown as the server's problem.

Not done here, on purpose: auditing the reads of the log itself (the VA POC's reads are not audited either; the entries carry no report content), the Auditor's scope (Release 2), and any Playwright scenario (ticket 53). Decided 2026-10-25 (docs/agents/reviewer-decisions.md): reading the log is audited after all, for a VA POC and a Cyber Manager alike, as an `AUDIT_LOG_VIEWED` entry carrying the filter ids only, built by ticket 50.

Consequences:
- A Cyber Manager reassigned away from a System loses its history with it, even of the reviews they made; a VA POC still reads it.
- A Cyber Manager who has not reviewed anything reads an empty log rather than a `403`.
- `Need` has a fifth value; the access guard answers it in one more case.

Decided 2026-10-25 by the user (docs/agents/reviewer-decisions.md), items 2, 3 and 5 as built:
- **Item 2:** "acted at the reviewing step" is read as a committed review recorded in `submission_participation`. The alternative is any action of theirs on the VA Submission, a read included.
- **Item 3:** without `submission=`, the history of every VA Submission reviewed is listed, so the "same filters" hold. The alternative is to require `submission=` from a Cyber Manager (`400` without it).
- **Item 5:** the history lists every entry about the VA Submission, other people's reads included. The alternative is to leave out reads (`*_VIEWED`) by others and keep only the changes.
- **Reads of the log:** audited (`AUDIT_LOG_VIEWED`, filter ids only), built by ticket 50; this ticket leaves them unaudited.

Amends ADR 0014 (the log is read by a Cyber Manager too, within a scope) and ADR 0013 (a new `Need`, `AUDIT`).
