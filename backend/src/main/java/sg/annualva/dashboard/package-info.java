/**
 * The per-role dashboards, as read models over the other modules (stories 18 and 71). Today only the SMO/Tech Lead's
 * inbox ({@code GET /api/dashboards/smo-inbox}): the caller's Systems for a VA Cycle with their Quarter, state, due dates
 * and next action. It stores nothing and changes nothing; what a person should do next is decided here and never in
 * the browser. Depends on access, cycle, register and web only.
 */
package sg.annualva.dashboard;
