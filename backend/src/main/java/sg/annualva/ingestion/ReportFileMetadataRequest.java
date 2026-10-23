package sg.annualva.ingestion;

import org.jspecify.annotations.Nullable;

/**
 * The scan date and target an SMO/Tech Lead confirms or corrects for a parsed report file (story 25). Both are read as text
 * and checked by the service, so that a wrong one is a 400 that names it.
 *
 * @param scanDate the business date the scan was made, written yyyy-mm-dd
 * @param target the project, host or application that was scanned, one line
 */
public record ReportFileMetadataRequest(@Nullable String scanDate, @Nullable String target) {}
