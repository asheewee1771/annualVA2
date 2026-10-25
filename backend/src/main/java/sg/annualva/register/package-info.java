/**
 * The System Register (stories 1, 2 and 12 to 14): the VA POC's standing list of Systems with their SMO/Tech Lead,
 * Cyber Manager and Quarter, loaded from a fixed Excel template. It hands out the template
 * ({@code GET /api/system-register/template}, VA POC only, audited) and reads an uploaded file into a Register change
 * preview that changes nothing ({@code POST /api/system-register/uploads}, ADR 0017), applies a confirmed preview with a
 * fresh sign-in ({@code POST /api/system-register/previews/{id}/apply}, ADR 0018) and lists the Systems
 * ({@code GET /api/systems}). A preview names each row's effects on in-flight work, and the apply withdraws what the VA POC
 * confirmed (ADR 0049), through {@link sg.annualva.register.InFlightWork}, which the submissions implement. It holds the
 * SMO Unreachable flag (story 13, ADR 0052), which {@link sg.annualva.register.SmoReachability} sets and clears on the
 * mail relay's answers, and an apply naming another SMO/Tech Lead clears.
 * Depends on access, audit, cycle, scan and web only.
 */
package sg.annualva.register;
