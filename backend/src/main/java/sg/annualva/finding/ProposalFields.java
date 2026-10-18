package sg.annualva.finding;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.ReportText;
import sg.annualva.web.RejectedRequestException;

/**
 * Reads the body of a proposal (IM8 as-1): the status, the fields it requires and no others, each free text cleaned and
 * bounded, the target date a real future date. Every refusal is a 400 {@code invalid-field} that names the field; the
 * sentence is built from constants of this class and never holds anything the person typed.
 *
 * <p>A text is cleaned by {@link ReportText}, the one rule for every text kept from a person or a report: control and
 * formatting characters (direction overrides, tag characters, word joiners, the zero-width space, the byte order mark),
 * private-use and unassigned code points and lone surrogates are removed, new lines and tabs stay in a long text and become
 * a space in a name or a role, and the result is trimmed; a text that is empty after that is a missing field. A long text is at
 * most {@value #MAX_TEXT} characters, a name or a role {@value #MAX_NAME}, counted in code points.
 */
final class ProposalFields {

    static final int MAX_TEXT = 4000;
    static final int MAX_NAME = 200;
    /** A bound that keeps a date a real one (as-1): the target date is at most this many years from today. */
    static final int MAX_YEARS_AHEAD = 10;

    /** The form of an id in a request body, checked before it is read as one. */
    static final Pattern ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** What a proposal holds once every field of it is read. Only the fields of the status are set. */
    record Parsed(
            FindingStatus status, @Nullable String evidence, @Nullable String justification, @Nullable String reason,
            @Nullable String approverName, @Nullable String approverRole, @Nullable UUID approvalMemoFileId,
            @Nullable String plan, @Nullable LocalDate targetDate) {}

    /**
     * Remediated, as a rescan proposes it for a Finding it reports nowhere (story 39): the evidence names the rescan, the
     * scan date its report gives (left out when it gives none) and the date it was uploaded, both business dates. The name
     * is the cleaned display name of an upload (at most 100 code points), so the text is always within {@link #MAX_TEXT}.
     */
    static Parsed remediatedByRescan(String name, @Nullable LocalDate scannedOn, LocalDate uploadedOn) {
        String when = (scannedOn == null ? "" : "scanned " + scannedOn + ", ") + "uploaded " + uploadedOn;
        String evidence = "Not reported by the rescan " + name + " (" + when
                + "), nor by any other file of this Scan Report.";
        return new Parsed(FindingStatus.REMEDIATED, evidence, null, null, null, null, null, null, null);
    }

    /** The fields in the order they are checked, so the first problem reported is always the same one. */
    private enum Field {
        EVIDENCE("evidence"),
        JUSTIFICATION("justification"),
        REASON("reason"),
        APPROVER_NAME("approverName"),
        APPROVER_ROLE("approverRole"),
        APPROVAL_MEMO("approvalMemoFileId"),
        PLAN("plan"),
        TARGET_DATE("targetDate");

        private final String name;

        Field(String name) {
            this.name = name;
        }

        @Nullable String of(ProposalRequest request) {
            return switch (this) {
                case EVIDENCE -> request.evidence();
                case JUSTIFICATION -> request.justification();
                case REASON -> request.reason();
                case APPROVER_NAME -> request.approverName();
                case APPROVER_ROLE -> request.approverRole();
                case APPROVAL_MEMO -> request.approvalMemoFileId();
                case PLAN -> request.plan();
                case TARGET_DATE -> request.targetDate();
            };
        }
    }

    private static Set<Field> required(FindingStatus status) {
        return switch (status) {
            case REMEDIATED -> EnumSet.of(Field.EVIDENCE);
            case FALSE_POSITIVE -> EnumSet.of(Field.JUSTIFICATION);
            case RISK_ACCEPTED -> EnumSet.of(Field.REASON, Field.APPROVER_NAME, Field.APPROVER_ROLE);
            case REMEDIATION_PLANNED -> EnumSet.of(Field.PLAN, Field.TARGET_DATE);
        };
    }

    /** The approval memo is the one optional field: a Risk Accepted proposal may carry one. */
    private static Set<Field> optional(FindingStatus status) {
        return status == FindingStatus.RISK_ACCEPTED ? EnumSet.of(Field.APPROVAL_MEMO) : EnumSet.noneOf(Field.class);
    }

    private ProposalFields() {}

    /**
     * @param today the business date (Asia/Singapore) the target date must be after
     * @throws RejectedRequestException 400 {@code invalid-field} naming the field that is missing, not used with the
     *     status, too long or not in the form it must be
     */
    static Parsed parse(ProposalRequest request, LocalDate today) {
        FindingStatus status = status(request.status());
        Set<Field> required = required(status);
        Set<Field> optional = optional(status);
        String evidence = null;
        String justification = null;
        String reason = null;
        String approverName = null;
        String approverRole = null;
        UUID memo = null;
        String plan = null;
        LocalDate targetDate = null;
        for (Field field : Field.values()) {
            String raw = field.of(request);
            if (!required.contains(field) && !optional.contains(field)) {
                if (raw != null) {
                    throw refused(field, "is not used with " + status.label() + ".");
                }
                continue;
            }
            if (raw == null) {
                if (required.contains(field)) {
                    throw refused(field, "is required for " + status.label() + ".");
                }
                continue;
            }
            switch (field) {
                case EVIDENCE -> evidence = text(field, raw, status, MAX_TEXT, false);
                case JUSTIFICATION -> justification = text(field, raw, status, MAX_TEXT, false);
                case REASON -> reason = text(field, raw, status, MAX_TEXT, false);
                case APPROVER_NAME -> approverName = text(field, raw, status, MAX_NAME, true);
                case APPROVER_ROLE -> approverRole = text(field, raw, status, MAX_NAME, true);
                case APPROVAL_MEMO -> memo = id(field, raw);
                case PLAN -> plan = text(field, raw, status, MAX_TEXT, false);
                case TARGET_DATE -> targetDate = date(field, raw, today);
            }
        }
        return new Parsed(status, evidence, justification, reason, approverName, approverRole, memo, plan, targetDate);
    }

    private static FindingStatus status(@Nullable String raw) {
        if (raw == null) {
            throw RejectedRequestException.invalidField("status", "The field 'status' is required.");
        }
        return Arrays.stream(FindingStatus.values()).filter(status -> status.name().equals(raw)).findFirst()
                .orElseThrow(() -> RejectedRequestException.invalidField("status",
                        "The field 'status' must be REMEDIATED, FALSE_POSITIVE, RISK_ACCEPTED or REMEDIATION_PLANNED."));
    }

    private static String text(Field field, String raw, FindingStatus status, int max, boolean singleLine) {
        String cleaned = singleLine ? ReportText.cleanOneLine(raw) : ReportText.cleanLongText(raw);
        if (cleaned.isEmpty()) {
            throw refused(field, "is required for " + status.label() + ".");
        }
        if (!ReportText.fits(cleaned, max)) {
            throw refused(field, "must be at most " + max + " characters.");
        }
        return cleaned;
    }

    private static UUID id(Field field, String raw) {
        if (!ID.matcher(raw).matches()) {
            throw refused(field, "must be the id of a supporting evidence file.");
        }
        return UUID.fromString(raw);
    }

    private static LocalDate date(Field field, String raw, LocalDate today) {
        LocalDate date;
        try {
            date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw refused(field, "must be a date written yyyy-mm-dd.");
        }
        if (!date.isAfter(today)) {
            throw refused(field, "must be a date after today.");
        }
        if (date.isAfter(today.plusYears(MAX_YEARS_AHEAD))) {
            throw refused(field, "must be within the next " + MAX_YEARS_AHEAD + " years.");
        }
        return date;
    }

    private static RejectedRequestException refused(Field field, String what) {
        return RejectedRequestException.invalidField(field.name, "The field '" + field.name + "' " + what);
    }
}
