package sg.annualva.ingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a parsed Scan Report file says about the scan, offered to the SMO/Tech Lead to confirm or correct (story 25), and
 * what they confirmed. Everything but the flags comes from the file's content or from what a person typed: it is data,
 * rendered only as text, and never logged or audited. It is read through its own call, which is audited for every role, so
 * the status the screen polls stays free of report content.
 *
 * @param fileId the file they were read from
 * @param scanDate the instant the report says the scan was made (UTC); null when the file names none
 * @param scanDay the business date of {@code scanDate} in Singapore, the date the screen offers; null when the file names none
 * @param scanDateIsExportTime true when the date is only when the file was exported, because the format records no other
 *     (a Burp Suite export): the screen says so, and the person is asked to give the date the scan was made; until they do,
 *     the file has no scan date to judge its age by
 * @param target the project, host or application the report says it scanned, offered as the target; null when it names none
 * @param confirmedScanDate the business date the SMO/Tech Lead says the scan was made; null until they confirm it. The age
 *     rule judges it before the report's own
 * @param confirmedTarget the target the SMO/Tech Lead confirmed or corrected; null until they confirm it
 * @param scanDateEdited true when the confirmed scan date differs from the business date of the file's own (for a Burp
 *     export, its export day), or the file named none: flagged for the reviewer, never refused, and still the date the age
 *     rule judges
 * @param targetEdited true when the confirmed target differs from the one the file names (or the file named none): flagged
 *     for the reviewer, never refused
 */
public record ReportFileMetadataView(
        UUID fileId, @Nullable Instant scanDate, @Nullable LocalDate scanDay, boolean scanDateIsExportTime,
        @Nullable String target, @Nullable LocalDate confirmedScanDate, @Nullable String confirmedTarget,
        boolean scanDateEdited, boolean targetEdited) {}
