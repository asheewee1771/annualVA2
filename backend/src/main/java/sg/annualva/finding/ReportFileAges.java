package sg.annualva.finding;

import java.util.List;
import java.util.UUID;

/**
 * The age of a VA Submission's report files (story 26), judged where the files are kept: the Scan Report upload pipeline
 * implements it, so the readiness check reads it without this module reaching up into that one. It names files only (their
 * id, the name the person gave and the Scan Report), never a scan date or anything else read from a report.
 */
public interface ReportFileAges {

    /**
     * Today's verdict on the VA Submission's live report files (parsed, and not replaced by a rescan), each list in the
     * order the files were uploaded. Must run in the caller's transaction.
     */
    Ages of(UUID submissionId);

    /**
     * @param tooOld the files whose scan date is more than 90 days before today's business date: they block submitting
     * @param undated the files with no scan date to judge (the report names none, or only the time it was exported, and
     *     the SMO/Tech Lead has not given one)
     */
    record Ages(List<NamedFile> tooOld, List<NamedFile> undated) {}

    /** A report file as a message names it: its id, the name the person gave it (data, shown as text) and its Scan Report. */
    record NamedFile(UUID fileId, String name, ReportType reportType) {}
}
