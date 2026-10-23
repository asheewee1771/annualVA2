package sg.annualva.ingestion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.finding.ReportFileReadiness;
import sg.annualva.finding.ReportType;
import sg.annualva.submission.SubmitReadiness.NamedFile;

/**
 * Where a VA Submission's files stand for submitting, at today's business date in Singapore (the {@code Clock} bean).
 *
 * <ul>
 *   <li>A file still on its way through the checks (any file, supporting evidence and a rescan included) blocks
 *       submitting until it finishes (reviewer decisions, "A VA Submission cannot leave Draft or Returned for Rework while
 *       any of its files is scanning").
 *   <li>A Scan Report is read when it holds a live report file: parsed, and not replaced by a rescan.
 *   <li>The age rule of story 26: a live report file whose scan date is more than {@value #MAX_AGE_DAYS} days before today
 *       blocks submitting, one with no scan date to judge must be given one, and one dated after today (possible only when
 *       the date was today's when it was read) blocks it too. The scan date judged is the one the SMO/Tech Lead confirmed,
 *       else the business date of the one the report names, unless that is only the time a Burp export was made, which is
 *       not a scan date (ADR 0031).
 * </ul>
 *
 * Everything is worked out from one read of the files, so no file is ever both still being checked and read.
 */
@Service
class FileReadiness implements ReportFileReadiness {

    /** The oldest a scan may be at submission, in days: a scan exactly this old still counts. */
    static final int MAX_AGE_DAYS = 90;

    private final ReportFileStore store;
    private final CycleCalendar calendar;

    FileReadiness(ReportFileStore store, CycleCalendar calendar) {
        this.store = store;
        this.calendar = calendar;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Files of(UUID submissionId) {
        LocalDate today = calendar.today();
        LocalDate oldestAllowed = today.minusDays(MAX_AGE_DAYS);
        List<NamedFile> scanning = new ArrayList<>();
        Set<ReportType> read = EnumSet.noneOf(ReportType.class);
        List<NamedFile> tooOld = new ArrayList<>();
        List<NamedFile> undated = new ArrayList<>();
        List<NamedFile> futureDated = new ArrayList<>();
        for (ReportFileStore.Row file : store.ofSubmission(submissionId)) {
            NamedFile named = new NamedFile(file.id(), file.displayName(), file.reportType().name());
            if (stillChecked(file.status())) {
                scanning.add(named);
            }
            if (file.status() != PipelineStatus.PARSED || file.supersededBy() != null) {
                continue;
            }
            read.add(file.reportType());
            @Nullable LocalDate scanned = ScanFacts.of(file, calendar).judgedDay();
            if (scanned == null) {
                undated.add(named);
            } else if (scanned.isBefore(oldestAllowed)) {
                tooOld.add(named);
            } else if (scanned.isAfter(today)) {
                futureDated.add(named);
            }
        }
        return new Files(scanning, read, tooOld, undated, futureDated);
    }

    /** A file rests once it was read (a report file), is ready (supporting evidence) or was rejected; until then it is checked. */
    private static boolean stillChecked(PipelineStatus status) {
        return status != PipelineStatus.PARSED && status != PipelineStatus.READY && !status.rejected();
    }
}
