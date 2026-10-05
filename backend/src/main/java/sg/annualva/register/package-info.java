/**
 * The System Register (stories 1, 2 and 12 to 14): the VA POC's standing list of Systems with their SMO/Tech Lead,
 * Cyber Manager and Quarter, loaded from a fixed Excel template. Today it hands out the template
 * ({@code GET /api/system-register/template}, VA POC only, audited); the upload, the Register change preview and the
 * apply arrive in later tickets. Depends on access and audit only.
 */
package sg.annualva.register;
