/**
 * The Findings read from a VA Submission's Scan Reports (stories 20a, 21, 25, 28 and 36 to 38): one Finding per identity
 * key in a VA Submission, with the places it was reported, listed for anyone who may see the System. The upload pipeline
 * hands it what a parser read from a file; the SMO/Tech Lead proposes a Finding Status for each (story 35), every proposal
 * a new row that is never changed and the Finding pointing at the latest, one at a time or for several in one Bulk-set
 * batch (story 37); it counts how far the Findings are (story 38). It also keeps how each Scan Report is provided (uploaded,
 * not applicable or entered by hand) and the Findings the SMO/Tech Lead types in (story 23). The review and carry-over
 * rules arrive with their own tickets. Depends on parser, submission, cycle, access, audit and web.
 */
package sg.annualva.finding;
