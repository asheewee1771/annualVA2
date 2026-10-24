/**
 * Exports (stories 81, 84 and 86, ticket 44): a person asks for an export with a fresh sign-in, the export job makes the
 * file in the background and keeps it in the file store's export zone (encrypted at rest) for 24 hours, and only the
 * person who asked downloads it. The first export is the VA POC's management summary in Excel: counts by Quarter and
 * state with Endorsed Late, and a by-System table, read through the Cycle matrix ({@code dashboard}) and the Findings'
 * open Deviation counts, and never a Finding. Every file carries its classification in its header, footer and first row:
 * Confidential when it covers the complete set, otherwise Restricted / Sensitive-Normal (assumption A-1). Asking and
 * downloading are audited. Depends on access, audit, boundary, cycle, dashboard, finding and web only.
 */
package sg.annualva.export;
