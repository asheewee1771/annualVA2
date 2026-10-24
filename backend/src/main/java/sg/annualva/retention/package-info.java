/**
 * Retention and disposal (story 87, ticket 49, ADR 0048). Records are kept for the VA Cycle plus 3 years, a superseded Scan
 * Report file's content until its VA Submission is Endorsed, and an export's row until its 24 hours are up. The retention
 * job lists what is due on a disposal list ({@code GET /api/disposal-lists}), a VA POC approves the list with a fresh
 * sign-in ({@code POST /api/disposal-lists/{id}/approve}), and the executor deletes the file-store objects first, then the
 * records in foreign-key order, through the one deliberate path the append-only tables allow (V149), and writes one
 * tombstone audit entry with the counts and the list's id. Nothing else hard-deletes a record. Sits on top: depends on
 * access, audit, boundary, cycle, register and web only, and nothing reads it.
 */
package sg.annualva.retention;
