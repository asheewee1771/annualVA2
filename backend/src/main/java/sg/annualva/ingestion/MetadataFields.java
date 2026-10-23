package sg.annualva.ingestion;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.ReportText;
import sg.annualva.web.RejectedRequestException;

/**
 * Reads and bounds what an SMO/Tech Lead confirms of a report file (story 25, IM8 as-1), the scan date first, then the
 * target: the first one that is wrong is a 400 {@code invalid-field} that names it, in a fixed sentence that never repeats
 * what was sent.
 */
final class MetadataFields {

    /** The longest a target may be: the bound of the one the parser pre-fills (V10). */
    static final int MAX_TARGET = 200;
    /** How far back a scan date may be: a bound that keeps the date real; a scan that old blocks submitting anyway. */
    static final int MAX_YEARS_BACK = 10;

    private static final String SCAN_DATE = "scanDate";
    private static final String TARGET = "target";

    /** What was confirmed, read and cleaned. */
    record Parsed(LocalDate scanDate, String target) {}

    private MetadataFields() {}

    /**
     * @param today today's business date: the scan date may be today but not after it, and at most
     *     {@value #MAX_YEARS_BACK} years before it
     */
    static Parsed parse(ReportFileMetadataRequest request, LocalDate today) {
        LocalDate scanDate = scanDate(request.scanDate(), today);
        String target = request.target() == null ? "" : ReportText.cleanOneLine(request.target());
        if (target.isEmpty()) {
            throw refused(TARGET, "is required.");
        }
        if (!ReportText.fits(target, MAX_TARGET)) {
            throw refused(TARGET, "must be at most " + MAX_TARGET + " characters.");
        }
        return new Parsed(scanDate, target);
    }

    private static LocalDate scanDate(@Nullable String raw, LocalDate today) {
        if (raw == null) {
            throw refused(SCAN_DATE, "is required.");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw refused(SCAN_DATE, "must be a date written yyyy-mm-dd.");
        }
        if (date.isAfter(today)) {
            throw refused(SCAN_DATE, "must not be after today.");
        }
        if (date.isBefore(today.minusYears(MAX_YEARS_BACK))) {
            throw refused(SCAN_DATE, "must be within the last " + MAX_YEARS_BACK + " years.");
        }
        return date;
    }

    private static RejectedRequestException refused(String field, String what) {
        return RejectedRequestException.invalidField(field, "The field '" + field + "' " + what);
    }
}
