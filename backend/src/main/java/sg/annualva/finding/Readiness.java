package sg.annualva.finding;

import java.util.List;
import sg.annualva.submission.ReviewProgress;
import sg.annualva.submission.SubmitReadiness.Blocker;
import sg.annualva.submission.SubmitReadiness.NamedFile;

/**
 * How far a VA Submission is (stories 26, 38 and 40): what the progress bar shows, the report files whose age stands in the
 * way of submitting, and every reason it cannot be submitted yet. Counts, fixed sentences and file names only, never
 * report content.
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
 * @param blockers every reason the VA Submission cannot be submitted now apart from the Declaration, which the
 *     SMO/Tech Lead ticks when submitting; empty when nothing else stands in the way. The submit refuses with the same.
 * @param review where the Cyber Manager's review stands: whether it may be returned for rework (story 55) and what stands
 *     in the way of passing it to endorsement (story 57), by the rule those commands refuse by
 */
public record Readiness(
        long total, long proposed, long unproposed, long awaitingReview, long carriedOver, long targetDateMissed,
        long disputed, List<NamedFile> tooOld, List<NamedFile> undated, List<Blocker> blockers,
        ReviewProgress.Outcome review) {}
