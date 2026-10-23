package sg.annualva.ingestion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import sg.annualva.cycle.CycleCalendar;

/**
 * What a parsed report file says about its scan and what the SMO/Tech Lead confirmed of it (stories 25 and 26, ADR 0031),
 * worked out once: the business date of the file's own date, whether that date is only a Burp export time, the date the
 * age rule judges and whether the scan date or the target was edited. The metadata the screen shows and the age rule both read it, so the
 * two never disagree. Everything but the flags is report content or typed text: data, never logged or audited.
 *
 * @param fileInstant the instant the report says the scan was made (UTC); null when it names none
 * @param fileDay the business date of {@code fileInstant} in Singapore; null when the report names no date
 * @param exportTimeOnly true when the report's only date is when it was exported (a Burp Suite export), not a scan date
 * @param fileTarget the target the report names; null when it names none
 * @param confirmedDay the business date the SMO/Tech Lead says the scan was made; null until they confirm it
 * @param confirmedTarget the target the SMO/Tech Lead confirmed or corrected; null until they confirm it
 */
record ScanFacts(
        @Nullable Instant fileInstant, @Nullable LocalDate fileDay, boolean exportTimeOnly, @Nullable String fileTarget,
        @Nullable LocalDate confirmedDay, @Nullable String confirmedTarget) {

    /** The facts of a parsed report file, its date read as a business date in Singapore. */
    static ScanFacts of(ReportFileStore.Row file, CycleCalendar calendar) {
        Instant scanned = file.scanDateFromFile();
        // A parsed file was given its report format by the type check.
        boolean exportTime = ReportFormat.valueOf(Objects.requireNonNull(file.detectedFormat())).scanDateIsExportTime();
        return new ScanFacts(scanned, scanned == null ? null : calendar.dateOf(scanned), exportTime, file.targetFromFile(),
                file.scanDateConfirmed(), file.targetConfirmed());
    }

    /**
     * The scan date the age rule judges: the one confirmed, else the report's own unless that is only an export time; null
     * when there is none to judge (the file is undated).
     */
    @Nullable LocalDate judgedDay() {
        if (confirmedDay != null) {
            return confirmedDay;
        }
        return exportTimeOnly ? null : fileDay;
    }

    /**
     * True when a confirmed scan date differs from the business date of the report's own (for a Burp export, its export
     * day: one rule for every format), or the report named none: flagged, never refused, and still the date judged.
     */
    boolean scanDateEdited() {
        return confirmedDay != null && !confirmedDay.equals(fileDay);
    }

    /** True when a confirmed target differs from the one the report names, compared exactly, or the report named none. */
    boolean targetEdited() {
        return confirmedTarget != null && !confirmedTarget.equals(fileTarget);
    }

    /** The metadata of the file as the metadata read and the confirmation answer it. */
    ReportFileMetadataView view(UUID fileId) {
        return new ReportFileMetadataView(fileId, fileInstant, fileDay, exportTimeOnly, fileTarget, confirmedDay,
                confirmedTarget, scanDateEdited(), targetEdited());
    }
}
