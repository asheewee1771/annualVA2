package sg.annualva.finding;

import java.util.List;

/**
 * How far a VA Submission is (stories 26 and 38): what the progress bar shows, and the report files whose age stands in the
 * way of submitting. Counts and file names only, never report content.
 *
 * @param total every Finding of the VA Submission
 * @param proposed the Findings that have a proposed Finding Status, whatever its review
 * @param unproposed the Findings with no proposed Finding Status yet: what the SMO/Tech Lead still has to propose
 * @param awaitingReview the Findings whose current proposal awaits the Cyber Manager's review (review state PENDING)
 * @param carriedOver the Carried Over Findings still to be confirmed again; none until carry-over arrives (Release 2)
 * @param targetDateMissed the Target Date Missed Findings; none until that rule arrives (Release 2)
 * @param disputed the Findings whose current proposal the Cyber Manager Disputed
 * @param tooOld the report files more than 90 days old at today's business date, by name, in upload order
 * @param undated the report files with no scan date to judge, by name, in upload order: the SMO/Tech Lead gives one
 */
public record Readiness(
        long total, long proposed, long unproposed, long awaitingReview, long carriedOver, long targetDateMissed,
        long disputed, List<ReportFileAges.NamedFile> tooOld, List<ReportFileAges.NamedFile> undated) {}
