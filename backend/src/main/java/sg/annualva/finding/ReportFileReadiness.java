package sg.annualva.finding;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import sg.annualva.submission.SubmitReadiness.NamedFile;

/**
 * Where a VA Submission's files stand for submitting (stories 23, 26 and 40), judged where the files are kept: the Scan
 * Report upload pipeline implements it, so the readiness check reads it without this module reaching up into that one. It
 * names files only (their id, the name the person gave and the Scan Report), never a scan date or anything else read from a
 * report.
 */
public interface ReportFileReadiness {

    /**
     * Today's verdict on the VA Submission's files, each list in the order the files were uploaded, read from one view of
     * them so that no file is counted both as still being checked and as read. Must run in the caller's transaction.
     */
    Files of(UUID submissionId);

    /**
     * @param scanning the files (report files and supporting evidence, a rescan included) still on their way through the
     *     checks: they block submitting until they finish
     * @param read the Scan Reports that hold a live report file whose Findings were read (parsed, and not replaced by a
     *     rescan)
     * @param tooOld the live report files whose scan date is more than 90 days before today's business date
     * @param undated the live report files with no scan date to judge (the report names none, or only the time it was
     *     exported, and the SMO/Tech Lead has not given one)
     * @param futureDated the live report files whose scan date is after today's business date (the pipeline rejects such a
     *     file, so only a date that was today's when it was read and is no longer can be)
     */
    record Files(List<NamedFile> scanning, Set<ReportType> read, List<NamedFile> tooOld, List<NamedFile> undated,
            List<NamedFile> futureDated) {

        public Files {
            scanning = List.copyOf(scanning);
            read = Set.copyOf(read);
            tooOld = List.copyOf(tooOld);
            undated = List.copyOf(undated);
            futureDated = List.copyOf(futureDated);
        }
    }
}
