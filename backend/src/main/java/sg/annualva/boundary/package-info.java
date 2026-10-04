/**
 * The adapter interfaces at the boundary of the application: identity provider, malware scanner,
 * mail relay, file store, and the clock (java.time.Clock, a bean in the app package). These are
 * the only things Seam 1 tests fake. Each interface arrives with the first slice that needs it;
 * production implementations live only under sg.annualva.adapters.
 */
package sg.annualva.boundary;
