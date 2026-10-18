package sg.annualva.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import sg.annualva.finding.ReportType;

/**
 * A Scan Report file as the API reports it: where it is in the pipeline and, when it was rejected, why. Nothing in it
 * comes from the file's content; the name is the one the person gave it, stripped of any path and control character.
 *
 * @param name the file name as shown to the person; it is data, never markup
 * @param uploadSha256 the SHA-256 of the bytes as received, lower-case hex
 * @param status where the file is in the pipeline
 * @param rejectCode why it was rejected; null for a file that was not
 * @param message the plain sentence for a rejected file (the same every time for a code); null otherwise
 * @param scanAttempts scan attempts made so far, at most three
 * @param detectedFormat the format the bytes were found to be; null before the type check has run
 * @param uploadedAt when it was received (UTC)
 * @param replacesId the parsed report file this one is a rescan of (story 39); null for an ordinary upload
 * @param supersededBy the rescan that replaced this file once that rescan was parsed; null while it has not been. A
 *     superseded file is kept, and can still be downloaded, until the retention ticket disposes of it
 * @param stages the order the file went through its stages, one entry each time it entered a status
 */
public record ReportFileView(
        UUID id, UUID submissionId, ReportType reportType, FileKind kind, String name, long sizeBytes,
        String uploadSha256, PipelineStatus status, @Nullable RejectCode rejectCode, @Nullable String message, int scanAttempts,
        @Nullable String detectedFormat, Instant uploadedAt, @Nullable UUID replacesId, @Nullable UUID supersededBy,
        List<Stage> stages) {

    /** One status the file entered, and when (UTC). */
    public record Stage(PipelineStatus status, Instant at) {}
}
