package sg.annualva.submission;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import sg.annualva.cycle.Quarter;
import sg.annualva.parser.ReportText;
import sg.annualva.web.RejectedRequestException;

/**
 * The fields of an Exemption request, read and checked (stories 43 and 47, IM8 as-1). A VA done in the 3 months before the
 * Quarter (from the day 3 months before its first day to the day before it, and not after today) is a prior VA; one
 * scheduled in the 3 months after it (from the day after its last day to 3 months after that) is a scheduled VA, the basis
 * of a conditional Exemption. The evidence is long text, cleaned by the one text rule and at most
 * {@value ReportText#MAX_LONG_TEXT} characters. Each wrong field is a 400 {@code invalid-field} naming it, with a fixed
 * sentence that never echoes what was sent.
 */
final class ExemptionFields {

    /** How long before and after the Quarter a VA may fall (spec story 43). */
    static final int MONTHS = 3;

    /** What was requested, read and cleaned. */
    record Parsed(Exemptions.Basis basis, LocalDate vaDate, String evidence, @Nullable UUID evidenceFileId) {}

    private static final String BASIS = "basis";
    private static final String VA_DATE = "vaDate";
    private static final String EVIDENCE = "evidence";

    private ExemptionFields() {}

    /**
     * @param quarter the Quarter of the VA Submission the request freezes
     * @param today today's business date
     */
    static Parsed parse(Exemptions.Request request, Quarter quarter, LocalDate today) {
        Exemptions.Basis basis = basis(request.basis());
        LocalDate vaDate = vaDate(request.vaDate(), basis, quarter, today);
        String evidence = request.evidence() == null ? "" : ReportText.cleanLongText(request.evidence());
        if (evidence.isEmpty()) {
            throw refused(EVIDENCE, "is required.");
        }
        if (!ReportText.fits(evidence, ReportText.MAX_LONG_TEXT)) {
            throw refused(EVIDENCE, "must be at most " + ReportText.MAX_LONG_TEXT + " characters.");
        }
        return new Parsed(basis, vaDate, evidence, request.evidenceFileId());
    }

    /** The last day a conditional Exemption's VA may be recorded by: the Quarter's last day plus 3 months (story 47). */
    static LocalDate lapsesOn(Quarter quarter) {
        return quarter.endsOn().plusMonths(MONTHS);
    }

    private static Exemptions.Basis basis(@Nullable String raw) {
        return Arrays.stream(Exemptions.Basis.values()).filter(basis -> basis.name().equals(raw)).findFirst()
                .orElseThrow(() -> refused(BASIS, "must be PRIOR_VA or SCHEDULED_VA."));
    }

    private static LocalDate vaDate(@Nullable String raw, Exemptions.Basis basis, Quarter quarter, LocalDate today) {
        if (raw == null) {
            throw refused(VA_DATE, "is required.");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw refused(VA_DATE, "must be a date written yyyy-mm-dd.");
        }
        LocalDate from = basis == Exemptions.Basis.PRIOR_VA ? quarter.startsOn().minusMonths(MONTHS)
                : quarter.endsOn().plusDays(1);
        LocalDate to = basis == Exemptions.Basis.PRIOR_VA ? quarter.startsOn().minusDays(1) : lapsesOn(quarter);
        if (date.isBefore(from) || date.isAfter(to)) {
            throw refused(VA_DATE, basis == Exemptions.Basis.PRIOR_VA
                    ? "must fall within the 3 months before the Quarter, from " + from + " to " + to + "."
                    : "must fall within the 3 months after the Quarter, from " + from + " to " + to + ".");
        }
        if (basis == Exemptions.Basis.PRIOR_VA && date.isAfter(today)) {
            throw refused(VA_DATE, "must not be after today for a VA already done.");
        }
        return date;
    }

    private static RejectedRequestException refused(String field, String what) {
        return RejectedRequestException.invalidField(field, "The field '" + field + "' " + what);
    }
}
