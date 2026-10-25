/**
 * Boundary adapters whose production implementation arrives with ticket 51 (real adapters under dev-mcc): the object
 * store, the malware scanner, the mail relay and the identity provider's account status. Each fails as an unreachable
 * service, so nothing is stored, called clean or mailed until the real one is wired, and the account-status check leaves
 * sessions as they were (the sign-in callback still refuses a person the token reports disabled).
 */
package sg.annualva.adapters.pending;
