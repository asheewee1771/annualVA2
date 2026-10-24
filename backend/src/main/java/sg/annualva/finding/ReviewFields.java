package sg.annualva.finding;

import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.ReportText;
import sg.annualva.web.RejectedRequestException;

/**
 * Reads the body of a review (story 50) in a fixed order, {@code decision}, {@code comment}, then what the review is of
 * ({@code proposalId} for a Finding, {@code justificationVersion} for a not-applicable justification; the other is refused
 * when given), and answers the first wrong field with a 400 {@code invalid-field} that names it and echoes nothing typed
 * (as-13). The comment is cleaned and bounded by the rule every long text a person types follows (ADR 0027 item 5, IM8
 * as-1).
 */
final class ReviewFields {

    /** The longest a comment may be, in code points: the bound of every long text. */
    static final int MAX_COMMENT = ProposalFields.MAX_TEXT;

    private static final String DECISION = "decision";
    private static final String COMMENT = "comment";
    private static final String PROPOSAL_ID = "proposalId";
    private static final String JUSTIFICATION_VERSION = "justificationVersion";

    /** A version is a whole number of at most nine digits, so it always fits an {@code int}. */
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}");

    /**
     * A review as read.
     *
     * @param comment cleaned; null when none was given (or nothing was left once cleaned) on a confirmation
     * @param proposalId the proposal reviewed; null for a not-applicable justification
     * @param justificationVersion the version of the justification reviewed; null for a Finding
     */
    record Parsed(ReviewDecision decision, @Nullable String comment, @Nullable UUID proposalId,
            @Nullable Integer justificationVersion) {}

    private ReviewFields() {}

    /** A review of a Finding's proposal: the proposal reviewed is required. */
    static Parsed ofProposal(ReviewRequest request) {
        ReviewDecision decision = decision(request.decision());
        String comment = comment(decision, request.comment());
        String raw = request.proposalId();
        if (raw == null) {
            throw RejectedRequestException.invalidField(PROPOSAL_ID, "The field 'proposalId' is required.");
        }
        if (!ProposalFields.ID.matcher(raw).matches()) {
            throw RejectedRequestException.invalidField(PROPOSAL_ID,
                    "The field 'proposalId' must be the id of the proposal reviewed.");
        }
        if (request.justificationVersion() != null) {
            throw RejectedRequestException.invalidField(JUSTIFICATION_VERSION,
                    "The field 'justificationVersion' is not used to review a Finding.");
        }
        return new Parsed(decision, comment, UUID.fromString(raw), null);
    }

    /** A review of a not-applicable justification: the version reviewed is required, and there is no proposal. */
    static Parsed ofJustification(ReviewRequest request) {
        ReviewDecision decision = decision(request.decision());
        String comment = comment(decision, request.comment());
        if (request.proposalId() != null) {
            throw RejectedRequestException.invalidField(PROPOSAL_ID,
                    "The field 'proposalId' is not used to review a not-applicable justification.");
        }
        String raw = request.justificationVersion();
        if (raw == null) {
            throw RejectedRequestException.invalidField(JUSTIFICATION_VERSION,
                    "The field 'justificationVersion' is required.");
        }
        if (!VERSION.matcher(raw).matches()) {
            throw RejectedRequestException.invalidField(JUSTIFICATION_VERSION,
                    "The field 'justificationVersion' must be the version of the justification reviewed.");
        }
        return new Parsed(decision, comment, null, Integer.valueOf(raw));
    }

    private static ReviewDecision decision(@Nullable String raw) {
        if (raw == null) {
            throw RejectedRequestException.invalidField(DECISION, "The field 'decision' is required.");
        }
        return Arrays.stream(ReviewDecision.values()).filter(each -> each.name().equals(raw)).findFirst()
                .orElseThrow(() -> RejectedRequestException.invalidField(DECISION,
                        "The field 'decision' must be CONFIRMED or DISPUTED."));
    }

    private static @Nullable String comment(ReviewDecision decision, @Nullable String raw) {
        String cleaned = raw == null ? "" : ReportText.cleanLongText(raw);
        if (cleaned.isEmpty()) {
            if (decision == ReviewDecision.DISPUTED) {
                throw RejectedRequestException.invalidField(COMMENT,
                        "The field 'comment' is required to Dispute: say what is wrong.");
            }
            return null;
        }
        if (!ReportText.fits(cleaned, MAX_COMMENT)) {
            throw RejectedRequestException.invalidField(COMMENT,
                    "The field 'comment' must be at most " + MAX_COMMENT + " characters.");
        }
        return cleaned;
    }
}
