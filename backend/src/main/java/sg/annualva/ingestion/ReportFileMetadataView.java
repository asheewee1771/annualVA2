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
 * @param scanDate the instant the report says the scan was made (UTC); null when the file names none or its format has no
 *     parser yet
 * @param target the project the report says it scanned, offered as the target; null as above
 */
public record ReportFileMetadataView(UUID fileId, @Nullable Instant scanDate, @Nullable String target) {}
