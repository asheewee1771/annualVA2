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

    /** That a scheduled VA happened (ticket 42), read and cleaned. */
    record Happened(LocalDate vaDate, String evidence) {}

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
        LocalDate vaDate = vaDate(request.vaDate(), window(basis, quarter), today);
        return new Parsed(basis, vaDate, evidence(request.evidence()), request.evidenceFileId());
    }

    /**
     * That a conditional Exemption's scheduled VA happened (story 47): on a day of the scheduled VA's window (from the day
     * after the Quarter's last day to 3 months after it) that is not after today, with what shows it happened, as long
     * text by the same rule as a request's evidence.
     *
     * @param quarter the Quarter of the Exempted VA Submission
     * @param today today's business date
     */
    static Happened happened(Exemptions.VaHappened happened, Quarter quarter, LocalDate today) {
        Window scheduled = window(Exemptions.Basis.SCHEDULED_VA, quarter);
        LocalDate vaDate = vaDate(happened.vaDate(), new Window(scheduled.from(), scheduled.to(), scheduled.side(), true),
                today);
        return new Happened(vaDate, evidence(happened.evidence()));
    }

    private static String evidence(@Nullable String raw) {
        return ReportText.longText(EVIDENCE, raw, ReportText.MAX_LONG_TEXT, RejectedRequestException::invalidField)
                .orElseThrow(() -> refused(EVIDENCE, "is required."));
    }

    /** The last day a conditional Exemption's VA may be recorded by: the Quarter's last day plus 3 months (story 47). */
    static LocalDate lapsesOn(Quarter quarter) {
        return quarter.endsOn().plusMonths(MONTHS);
    }

    private static Exemptions.Basis basis(@Nullable String raw) {
        return Arrays.stream(Exemptions.Basis.values()).filter(basis -> basis.name().equals(raw)).findFirst()
                .orElseThrow(() -> refused(BASIS, "must be PRIOR_VA or SCHEDULED_VA."));
    }

    private static LocalDate vaDate(@Nullable String raw, Window window, LocalDate today) {
        if (raw == null) {
            throw refused(VA_DATE, "is required.");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw refused(VA_DATE, "must be a date written yyyy-mm-dd.");
        }
        if (date.isBefore(window.from()) || date.isAfter(window.to())) {
            throw refused(VA_DATE, "must fall within the 3 months " + window.side() + " the Quarter, from "
                    + window.from() + " to " + window.to() + ".");
        }
        if (window.done() && date.isAfter(today)) {
            throw refused(VA_DATE, "must not be after today for a VA already done.");
        }
        return date;
    }

    /**
     * The days a VA of this basis may fall on, both included, and whether it is one already done.
     *
     * @param side "before" or "after" the Quarter, for the refusal's sentence
     */
    private record Window(LocalDate from, LocalDate to, String side, boolean done) {}

    private static Window window(Exemptions.Basis basis, Quarter quarter) {
        return switch (basis) {
            case PRIOR_VA -> new Window(quarter.startsOn().minusMonths(MONTHS), quarter.startsOn().minusDays(1), "before",
                    true);
            case SCHEDULED_VA -> new Window(quarter.endsOn().plusDays(1), lapsesOn(quarter), "after", false);
        };
    }

    private static RejectedRequestException refused(String field, String what) {
        return RejectedRequestException.invalidField(field, "The field '" + field + "' " + what);
    }
}
