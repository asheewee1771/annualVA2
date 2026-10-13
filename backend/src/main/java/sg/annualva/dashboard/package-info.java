/**
 * The per-role dashboards, as read models over the other modules (stories 18 and 71). Today only the SMO/Tech Lead's
 * inbox ({@code GET /api/dashboards/smo-inbox}): the caller's Systems for a VA Cycle with their Quarter, state, due
 * dates, next action and, once started, their VA Submission. It stores nothing and changes nothing; what a person should
 * do next is decided here and never in the browser. Depends on access, cycle, register, submission and web only.
 */
package sg.annualva.dashboard;
