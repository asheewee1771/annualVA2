/**
 * Boundary adapters whose production implementation arrives with ticket 51 (real adapters under dev-mcc): the object
 * store and the malware scanner. Each fails closed, so nothing is stored or called clean until the real one is wired.
 */
package sg.annualva.adapters.pending;
