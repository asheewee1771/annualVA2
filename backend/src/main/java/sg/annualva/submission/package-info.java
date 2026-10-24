/**
 * The VA Submission (story 19): one System's evidence package for one VA Cycle. An SMO/Tech Lead starts it
 * ({@code POST /api/submissions}, ADR 0020), which creates it in Draft with its windows taken from the Quarter and
 * records the caller at the submitting step, and anyone who may see the System reads it
 * ({@code GET /api/submissions/{id}}). The SMO/Tech Lead submits it (ADR 0032); the Cyber Manager returns it for rework
 * or passes it to endorsement (ADR 0037). The Overdue sweep flags what passed its window (ADR 0033). It owns the state
 * machine and the segregation-of-duties guard (ADR 0035). Depends on access, audit, cycle, register, web, and parser for
 * the one text rule only.
 */
package sg.annualva.submission;
