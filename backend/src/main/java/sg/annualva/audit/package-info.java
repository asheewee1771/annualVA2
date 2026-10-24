/**
 * The audit log (IM8 lm-4, lm-15, lm-19; story 84): an append-only table of who did what, written in the
 * caller's transaction ({@link sg.annualva.audit.AuditLog#record}) or, for a refusal or a sign-in that no
 * transaction owns, in a transaction of its own ({@link sg.annualva.audit.AuditLog#recordIndependently}), and
 * emitted as an ECS event on the separate {@code annualva.audit} logger. Entries refer to Systems, VA Submissions
 * and Findings by id and hold only allow-listed detail ({@link sg.annualva.audit.DetailKey}); nothing a person typed
 * and nothing from a report can reach one. Read through {@code GET /api/audit-entries}, within the caller's read scope
 * ({@link sg.annualva.audit.AuditReaders}, a port the VA Submissions answer, ADR 0038). Depends on config and web only.
 */
package sg.annualva.audit;
