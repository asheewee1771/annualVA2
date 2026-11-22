package sg.annualva.submission;

import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.StepUp;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.parser.ReportText;
import sg.annualva.web.RejectedRequestException;

/**
 * The fixed Declaration wording the SMO/Tech Lead accepts when submitting (stories 40 and 41). Each wording is a version,
 * never changed, and the current one is the highest. Version 1 is a placeholder seeded with the app, and says so. A VA POC
 * publishes each new version in the app (ticket 61, the decision of 2026-10-24): one VA POC with a fresh sign-in, no
 * second approver, and the version is current at once, so a submit that accepted an earlier one is refused as
 * {@code declaration-changed} ({@link Submissions#submit}).
 */
@Service
public class Declarations {

    /**
     * One version of the wording.
     *
     * @param placeholder true while the wording is not the agreed one: the screen marks it as a placeholder
     */
    public record Text(int version, String text, boolean placeholder) {}

    /**
     * One version as a VA POC reads the list of them.
     *
     * @param publishedBy the VA POC who published it; null for the placeholder seeded with the app
     * @param publishedByName their name as the single sign-on gave it; null when there is none
     * @param publishedAt when it was published, and so became current; null for the placeholder
     * @param current whether it is the version the SMO/Tech Leads accept now; every other one is superseded
     * @param submissionCount how many VA Submissions still kept accepted it at a submit
     */
    public record Version(
            int version, String text, boolean placeholder, @Nullable UUID publishedBy, @Nullable String publishedByName,
            @Nullable Instant publishedAt, boolean current, int submissionCount) {}

    /**
     * The most UTF-16 characters a wording may be sent with: twice the rule's {@value ReportText#MAX_LONG_TEXT}, so the
     * boundary never refuses a wording the rule accepts (a code point outside the Basic Multilingual Plane is two), unless
     * it is padded with more than that many characters the cleaning removes.
     */
    static final int MAX_SENT = 2 * ReportText.MAX_LONG_TEXT;

    /**
     * A new version's wording, as the VA POC typed it. The bound is checked where the request is read (IM8 as-1); the rule
     * itself is {@link #publish}'s.
     */
    public record Publish(@Nullable @Size(max = MAX_SENT) String text) {}

    /** The fixed code of the publish step-up (IM8 ac-2): the intent a fresh sign-in is started for. */
    static final String PUBLISH_INTENT = "declaration-publish";

    /** The field of a publish, as a refusal names it. */
    private static final String TEXT = "text";

    /** The sentence of a wording too long, the same whichever check finds it. */
    private static final String TOO_LONG =
            "The field '" + TEXT + "' must be at most " + ReportText.MAX_LONG_TEXT + " characters.";

    private final DeclarationStore store;
    private final StepUp stepUp;
    private final CurrentUser user;
    private final AuditLog audit;
    private final AuditActor actor;
    private final Clock clock;

    Declarations(DeclarationStore store, StepUp stepUp, CurrentUser user, AuditLog audit, AuditActor actor,
            Clock clock) {
        this.store = store;
        this.stepUp = stepUp;
        this.user = user;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    /** The wording the SMO/Tech Lead is asked to accept now. It is no one's data, so the read is not audited. */
    @Transactional(readOnly = true)
    public Text current() {
        return store.current();
    }

    /** Every version, newest first, the current one first. The wordings are no one's data, so the read is not audited. */
    @Transactional(readOnly = true)
    public List<Version> versions() {
        return store.versions();
    }

    /**
     * Publishes the wording as the next version, current at once. Refused first without a fresh sign-in (403
     * {@code step-up-required}); then a wording empty or too long by the one rule for long text ({@link ReportText}) is
     * 400 {@code invalid-field} naming {@code text}, audited as a refusal. A publish is audited by its number only.
     *
     * @return the version as the list shows it
     */
    @Transactional
    public Version publish(Publish request) {
        stepUp.require(PUBLISH_INTENT);
        String text = ReportText.longText(TEXT, request.text(), ReportText.MAX_LONG_TEXT, this::refused)
                .orElseThrow(() -> refused(TEXT, "The field '" + TEXT + "' is required."));
        store.holdPublishing();
        int version = store.publish(text, user.id(), clock.instant());
        audit.record(actor.attribute(AuditEvent.of(AuditAction.DECLARATION_PUBLISHED, AuditOutcome.SUCCESS))
                .with(DetailKey.DECLARATION_VERSION, version));
        return store.version(version);
    }

    /**
     * A wording sent longer than {@link #MAX_SENT}, refused where the request is read: the same 400 and the same audit
     * entry as a wording the rule finds too long.
     */
    public RejectedRequestException sentTooLong() {
        return refused(TEXT, TOO_LONG);
    }

    /** Audits a refused publish in a transaction of its own (ADR 0014), then answers 400; the sentence is fixed. */
    private RejectedRequestException refused(String field, String sentence) {
        audit.recordIndependently(actor.attribute(AuditEvent.of(AuditAction.DECLARATION_PUBLISHED, AuditOutcome.REFUSED))
                .with(DetailKey.REASON, "invalid-field"));
        return RejectedRequestException.invalidField(field, sentence);
    }
}
