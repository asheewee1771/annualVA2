package sg.annualva.ingestion;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a parsed Scan Report file says about the scan, offered to the SMO/Tech Lead to confirm or correct (story 25). Both
 * come from the file's content: they are data, rendered only as text, and never logged or audited. They are read through
 * their own call, which is audited for every role, so the status the screen polls stays free of report content.
 *
 * @param fileId the file they were read from
 * @param scanDate the instant the report says the scan was made (UTC); null when the file names none
 * @param scanDateIsExportTime true when the date is only when the file was exported, because the format records no other
 *     (a Burp Suite export): the screen says so, and the person is asked to give the date the scan was made
 * @param target the project, host or application the report says it scanned, offered as the target; null when it names none
 */
public record ReportFileMetadataView(UUID fileId, @Nullable Instant scanDate, boolean scanDateIsExportTime,
        @Nullable String target) {}
