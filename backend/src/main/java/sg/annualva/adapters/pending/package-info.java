/**
 * Boundary adapters whose production implementation arrives with ticket 51 (real adapters under dev-mcc): the object
 * store, the malware scanner and the mail relay. Each fails closed, so nothing is stored, called clean or mailed until the
 * real one is wired.
 */
package sg.annualva.adapters.pending;
