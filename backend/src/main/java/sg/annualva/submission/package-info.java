/**
 * The VA Submission (story 19): one System's evidence package for one VA Cycle. Today an SMO/Tech Lead starts it
 * ({@code POST /api/submissions}, ADR 0020), which creates it in Draft with its windows taken from the Quarter and
 * records the caller at the submitting step, and anyone who may see the System reads it
 * ({@code GET /api/submissions/{id}}). The Overdue sweep flags what passed its window (ADR 0033). It will own the state
 * machine and the segregation-of-duties guard as the transitions arrive. It answers the audit log's read scope: a Cyber
 * Manager reads the history of the VA Submissions they reviewed (ADR 0037). Depends on access, audit, cycle, register and
 * web only.
 */
package sg.annualva.submission;
