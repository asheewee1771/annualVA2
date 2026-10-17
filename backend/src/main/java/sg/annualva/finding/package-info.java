/**
 * The Findings read from a VA Submission's Scan Reports (stories 20a, 21, 25 and 28): one Finding per identity key in a VA
 * Submission, with the places it was reported, listed for anyone who may see the System. The upload pipeline hands it what
 * a parser read from a file; the SMO/Tech Lead proposes a Finding Status for each (story 35), every proposal a new row that
 * is never changed and the Finding pointing at the latest; the review and carry-over rules arrive with their own tickets.
 * Depends on parser, submission, cycle, access, audit and web.
 */
package sg.annualva.finding;
