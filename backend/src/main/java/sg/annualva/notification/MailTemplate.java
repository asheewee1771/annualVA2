package sg.annualva.notification;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The logical templates a required notification is mailed with (ADR 0046). The relay's own id for each is configuration
 * ({@code annualva.mail.templates}). A ticket that mails a new kind adds it here, to its migration's
 * {@code mail_outbox_template_check} and to the configuration of every profile.
 */
enum MailTemplate {
    /** Returned for Rework, by the Cyber Manager with what is Disputed (ADR 0043 item 6) or by a VA POC (ADR 0047). */
    RETURNED_FOR_REWORK,
    /** A window passed: the person whose step it is. */
    OVERDUE,
    /** The System Register moved or transferred the System. */
    REGISTER_CHANGE;

    /**
     * How a notification is mailed: its template and, for a Register change, what changed in the app's words.
     *
     * @param changeKind null for anything but a Register change
     */
    record Mailing(MailTemplate template, @Nullable String changeKind) {}

    /**
     * How a notification is mailed, or not at all when it is in-app only: the one place that says it for every action. The
     * required messages are the spec's (Notifications): deadline reminders (ticket 38), Overdue, Return for Rework and
     * Disputed, and Register changes. A submit and a pass to endorsement are in-app only (provisional, ADR 0046 item 2):
     * the reminders mail the Cyber Manager and the VA POCs before their windows end. So is the Cycle close's Not Endorsed
     * (ticket 41): the spec names it neither a required message nor an FYI. A VA POC's return (ticket 35, ADR 0047) is a
     * Return for Rework for both people it tells: the SMO/Tech Lead who reworks it and the Cyber Manager it comes back to.
     * An Exemption's request and decision (ticket 40) are in-app only (provisional, ADR 0048): the spec names neither a
     * required message. So are a conditional Exemption's recorded VA, its confirmation and its lapse (ticket 42,
     * provisional, ADR 0050): the lapse's new Submission Window is mailed by the deadline reminders (ticket 38) and, once
     * it passes, as Overdue.
     */
    static Optional<Mailing> of(NoticeAction action) {
        return switch (action) {
            case REWORK, VA_POC_REWORK, VA_POC_RETURNED -> mailed(RETURNED_FOR_REWORK, null);
            case SUBMIT_OVERDUE, REVIEW_OVERDUE, ENDORSE_OVERDUE -> mailed(OVERDUE, null);
            case SYSTEM_MOVED -> mailed(REGISTER_CHANGE, "Moved to another Quarter");
            case SYSTEM_TRANSFERRED_TO_YOU -> mailed(REGISTER_CHANGE, "Transferred to you");
            case SYSTEM_TRANSFERRED_FROM_YOU -> mailed(REGISTER_CHANGE, "Transferred from you");
            case REVIEW, ENDORSE, NOT_ENDORSED, DECIDE_EXEMPTION, EXEMPTION_APPROVED, EXEMPTION_REJECTED, CONFIRM_VA,
                    VA_CONFIRMED, EXEMPTION_LAPSED, EXEMPTION_LAPSED_NOT_ENDORSED -> Optional.empty();
        };
    }

    private static Optional<Mailing> mailed(MailTemplate template, @Nullable String changeKind) {
        return Optional.of(new Mailing(template, changeKind));
    }
}
