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
    REGISTER_CHANGE,
    /** A deadline reminder before the window ends (ticket 38): 5 days before, or 2 and 1 weeks before for the VA POCs. */
    REMINDER_DUE_SOON,
    /** A deadline reminder on the window's last day. */
    REMINDER_DUE,
    /** The weekly reminder while the step is Overdue. */
    OVERDUE_WEEKLY,
    /** The copy of a weekly Overdue reminder to the next person up: the relay has no cc, so it is a mail of its own. */
    REMINDER_COPY;

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
     * provisional, ADR 0051): the lapse's new Submission Window is mailed by the deadline reminders (ticket 38) and, once
     * it passes, as Overdue.
     */
    static Optional<Mailing> of(NoticeAction action) {
        return switch (action) {
            case REWORK, VA_POC_REWORK, VA_POC_RETURNED -> mailed(RETURNED_FOR_REWORK, null);
            case SUBMIT_OVERDUE, REVIEW_OVERDUE, ENDORSE_OVERDUE -> mailed(OVERDUE, null);
            case SYSTEM_MOVED -> mailed(REGISTER_CHANGE, "Moved to another Quarter");
            case SYSTEM_TRANSFERRED_TO_YOU -> mailed(REGISTER_CHANGE, "Transferred to you");
            case SYSTEM_TRANSFERRED_FROM_YOU -> mailed(REGISTER_CHANGE, "Transferred from you");
            case WITHDRAWN -> mailed(REGISTER_CHANGE, "Withdrawn");
            case SUBMIT_DUE_SOON, REVIEW_DUE_SOON, ENDORSE_DUE_IN_TWO_WEEKS, ENDORSE_DUE_IN_ONE_WEEK ->
                    mailed(REMINDER_DUE_SOON, null);
            case SUBMIT_DUE_TODAY, REVIEW_DUE_TODAY -> mailed(REMINDER_DUE, null);
            case SUBMIT_STILL_OVERDUE, REVIEW_STILL_OVERDUE -> mailed(OVERDUE_WEEKLY, null);
            case SUBMIT_OVERDUE_COPY, REVIEW_OVERDUE_COPY -> mailed(REMINDER_COPY, null);
            case REVIEW, ENDORSE, NOT_ENDORSED, DECIDE_EXEMPTION, EXEMPTION_APPROVED, EXEMPTION_REJECTED, CONFIRM_VA,
                    VA_CONFIRMED, EXEMPTION_LAPSED, EXEMPTION_LAPSED_NOT_ENDORSED -> Optional.empty();
        };
    }

    private static Optional<Mailing> mailed(MailTemplate template, @Nullable String changeKind) {
        return Optional.of(new Mailing(template, changeKind));
    }
}
