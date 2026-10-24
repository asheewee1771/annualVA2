package sg.annualva.finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.submission.SubmitReadiness;

/**
 * Everything that stands in the way of submitting a VA Submission apart from the Declaration (story 40), in one place, so
 * that the submit refuses with exactly what the prepare screen shows. In this order:
 *
 * <ol>
 *   <li>{@code files-scanning}: a file is still on its way through the checks (ticket 21, reviewer decisions).
 *   <li>{@code report-missing}, for each Scan Report in turn: Secure Configuration needs a report file whose Findings were
 *       read or Findings entered by hand; Dependency-Check and Burp Suite need the same, or to be marked not applicable
 *       (ticket 22). A Scan Report marked not applicable or entered by hand always has its justification.
 *   <li>{@code findings-unproposed}: a Finding has no proposed Finding Status (story 40).
 *   <li>{@code files-too-old}, {@code files-undated} and {@code files-future-dated}: the age rule (story 26, ticket 23).
 * </ol>
 *
 * The files are read first, in one statement: once none is being checked, no settlement of this VA Submission is under
 * way, so the Findings counted afterwards are all there are. The Target Date Missed block arrives with Release 2.
 */
@Component
class SubmitChecks implements SubmitReadiness {

    private final ReportFileReadiness files;
    private final ScanReportStore reports;
    private final FindingStore findings;
    private final ReviewChecks review;

    SubmitChecks(ReportFileReadiness files, ScanReportStore reports, FindingStore findings, ReviewChecks review) {
        this.files = files;
        this.reports = reports;
        this.findings = findings;
        this.review = review;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Blocker> blockers(UUID submissionId) {
        return readiness(submissionId).blockers();
    }

    /**
     * The progress counts, the files whose age stands in the way, every reason it cannot be submitted now, and what the
     * Cyber Manager's return and pass to endorsement judge by ({@link ReviewChecks}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Readiness readiness(UUID submissionId) {
        ReportFileReadiness.Files files = this.files.of(submissionId);
        Map<ReportType, ScanReportStore.Slot> slots = reports.ofSubmission(submissionId);
        FindingStore.Counts counts = findings.counts(submissionId);
        List<Blocker> blockers = new ArrayList<>();
        if (!files.scanning().isEmpty()) {
            blockers.add(new Blocker("files-scanning",
                    "These files are still being checked. Wait until every one has finished.", null, files.scanning()));
        }
        for (ReportType type : ReportType.values()) {
            ReportMode mode = slots.getOrDefault(type, ScanReportStore.Slot.UPLOADED).mode();
            if (mode == ReportMode.UPLOAD && !files.read().contains(type)) {
                blockers.add(new Blocker("report-missing", missing(type), type.name(), List.of()));
            }
        }
        if (counts.unproposed() > 0) {
            blockers.add(new Blocker("findings-unproposed", "Every Finding needs a proposed status: " + counts.unproposed()
                    + (counts.unproposed() == 1 ? " Finding has" : " Findings have") + " none yet.", null, List.of()));
        }
        if (!files.tooOld().isEmpty()) {
            blockers.add(new Blocker("files-too-old", "These report files are more than 90 days old. Replace each with a "
                    + "newer scan, or correct its scan date if the file has it wrong.", null, files.tooOld()));
        }
        if (!files.undated().isEmpty()) {
            blockers.add(new Blocker("files-undated",
                    "These report files have no scan date. Give the date each scan was made.", null, files.undated()));
        }
        if (!files.futureDated().isEmpty()) {
            blockers.add(new Blocker("files-future-dated",
                    "These report files are dated after today. Correct each scan date.", null, files.futureDated()));
        }
        return new Readiness(counts.total(), counts.proposed(), counts.unproposed(), counts.awaitingReview(), 0, 0,
                counts.disputed(), files.tooOld(), files.undated(), blockers, !review.disputed(submissionId).isEmpty(),
                review.unconfirmed(submissionId));
    }

    /** The fixed sentence for a Scan Report that is not provided; Secure Configuration cannot be marked not applicable. */
    private static String missing(ReportType type) {
        return switch (type) {
            case SECURE_CONFIGURATION -> "The Secure Configuration Scan Report is always required: upload a report file "
                    + "whose findings can be read, or enter its Findings by hand.";
            case DEPENDENCY_CHECK -> "The Dependency-Check Scan Report needs a report file whose findings were read, or "
                    + "to be marked not applicable or entered by hand.";
            case BURP_SUITE -> "The Burp Suite Scan Report needs a report file whose findings were read, or to be "
                    + "marked not applicable or entered by hand.";
        };
    }
}
