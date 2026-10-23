package sg.annualva.submission;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What stands in the way of submitting a VA Submission, apart from the Declaration (story 40). The Findings and their Scan
 * Reports are kept by the modules above this one, so they implement it, and the submit command reads it under its lock
 * on the VA Submission without reaching up into them. Each reason has a fixed code and sentence; the files a reason is
 * about are named by id, the name the person gave and their Scan Report, never by anything read from a report.
 */
public interface SubmitReadiness {

    /**
     * Every reason the VA Submission cannot be submitted now, in a fixed order; empty when nothing stands in the way. Must
     * run in the caller's transaction.
     */
    List<Blocker> blockers(UUID submissionId);

    /**
     * One reason the VA Submission cannot be submitted.
     *
     * @param reason a fixed code, such as {@code files-too-old}
     * @param message a fixed sentence saying what to do (a count at most, never text from a person or a report)
     * @param reportType the Scan Report the reason is about, for a Scan Report that is not provided; null otherwise
     * @param files the files the reason is about, in the order they were uploaded; empty for a reason about no file
     */
    record Blocker(String reason, String message, @Nullable String reportType, List<NamedFile> files) {

        public Blocker {
            files = List.copyOf(files);
        }
    }

    /** A file as a reason names it: its id, the name the person gave it (data, shown as text) and its Scan Report. */
    record NamedFile(UUID fileId, String name, String reportType) {}
}
