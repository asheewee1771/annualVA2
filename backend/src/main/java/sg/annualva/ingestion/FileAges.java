package sg.annualva.ingestion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.finding.ReportFileAges;

/**
 * The age rule of story 26, at today's business date in Singapore (the {@code Clock} bean): a report file whose scan date
 * is more than {@value #MAX_AGE_DAYS} days before today blocks submitting, and one with no scan date to judge must be given
 * one. The scan date judged is the one the SMO/Tech Lead confirmed, else the business date of the one the report names,
 * unless that is only the time a Burp export was made, which is not a scan date (ADR 0031). Only live report files count:
 * parsed, and not replaced by a rescan. A file dated after today never gets here: the pipeline rejected it.
 */
@Service
class FileAges implements ReportFileAges {

    /** The oldest a scan may be at submission, in days: a scan exactly this old still counts. */
    static final int MAX_AGE_DAYS = 90;

    private final ReportFileStore store;
    private final CycleCalendar calendar;

    FileAges(ReportFileStore store, CycleCalendar calendar) {
        this.store = store;
        this.calendar = calendar;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Ages of(UUID submissionId) {
        LocalDate oldestAllowed = calendar.today().minusDays(MAX_AGE_DAYS);
        List<NamedFile> tooOld = new ArrayList<>();
        List<NamedFile> undated = new ArrayList<>();
        for (ReportFileStore.Row file : store.liveParsed(submissionId)) {
            NamedFile named = new NamedFile(file.id(), file.displayName(), file.reportType());
            LocalDate scanned = ScanFacts.of(file, calendar).judgedDay();
            if (scanned == null) {
                undated.add(named);
            } else if (scanned.isBefore(oldestAllowed)) {
                tooOld.add(named);
            }
        }
        return new Ages(List.copyOf(tooOld), List.copyOf(undated));
    }
}
