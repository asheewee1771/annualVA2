/**
 * The VA Submission (story 19): one System's evidence package for one VA Cycle. An SMO/Tech Lead starts it
 * ({@code POST /api/submissions}, ADR 0020), which creates it in Draft with its windows taken from the Quarter and
 * records the caller at the submitting step, and anyone who may see the System reads it
 * ({@code GET /api/submissions/{id}}). The SMO/Tech Lead submits it (ADR 0032); the Cyber Manager returns it for rework or passes it to endorsement (ADR 0039);
 * a VA POC endorses it, after a fresh sign-in (ADR 0042). The Overdue sweep flags what passed its window (ADR 0033),
 * and {@link sg.annualva.submission.WaitingWork} answers the deadline reminders what waits on which window (ADR 0050).
 * The VA Cycle lifecycle opens a Cycle when its Q1 starts and closes it, by the job after its close date or early by a
 * VA POC ({@code POST /api/cycles/{year}/close}), making everything unfinished Not Endorsed (ADR 0044). An SMO/Tech
 * Lead requests an Exemption ({@code POST /api/systems/{id}/exemptions}), which freezes the VA Submission until a VA
 * POC approves or rejects it after a fresh sign-in (ADR 0048). It owns the state machine and the segregation-of-duties
 * guard (ADR 0035). It answers the audit log's read scope: a Cyber Manager reads the history of the VA Submissions they
 * reviewed (ADR 0038). Depends on access, audit, cycle, register, web, and parser for the one text rule only.
 */
package sg.annualva.submission;
