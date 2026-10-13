/**
 * The Scan Report upload pipeline (stories 20, 24, 27 and 78): an SMO/Tech Lead uploads files for a Scan Report, each
 * is quarantined, malware-scanned, type-checked and schema-checked before anything reads it, and nobody can view a file
 * until it is clean ({@code POST /api/submissions/{id}/files}, ADR 0021). The scan job ({@link
 * sg.annualva.ingestion.ScanPoller}) does the stages after the quarantine. The parsers (tickets 14 to 17) and supporting
 * evidence (ticket 18) hang off the status this module leaves a file in. Depends on submission, scan, parser, access,
 * audit and web.
 */
package sg.annualva.ingestion;
