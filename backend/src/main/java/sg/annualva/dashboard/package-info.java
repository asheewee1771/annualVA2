/**
 * The per-role dashboards, as read models over the other modules (stories 18, 48, 49, 64 and 71). The SMO/Tech Lead's
 * inbox ({@code GET /api/dashboards/smo-inbox}) lists the caller's Systems for a VA Cycle with their Quarter, state, due
 * dates, next action and, once started, their VA Submission. The Cyber Manager's inbox
 * ({@code GET /api/dashboards/cm-inbox}) lists the Systems assigned to the caller, Overdue first and then by the day the
 * current window ends, with their Findings by review and a marker where segregation of duties will refuse the caller's
 * review; the Quarter board ({@code GET /api/dashboards/quarter-board}) shows one Quarter in one column per state, to a
 * Cyber Manager their Systems and to a VA POC every System. The VA POC's Cycle matrix
 * ({@code GET /api/dashboards/cycle-matrix}) shows the whole Cycle: a strip of counts per Quarter and one row per System
 * with its milestones against the day each is due. It stores nothing and changes
 * nothing; what a person should do next is decided here and never in the browser. Depends on access, cycle, register,
 * submission, finding and web only.
 */
package sg.annualva.dashboard;
