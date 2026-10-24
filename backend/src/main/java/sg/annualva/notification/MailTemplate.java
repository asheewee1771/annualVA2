package sg.annualva.notification;

import java.util.Optional;
import java.util.Set;

/**
 * The logical templates a required notification is mailed with (ADR 0044), each with the value keys it accepts: the
 * System, the state, the action, the due date and the link, and for a Register change what changed (story 91, ticket
 * 57). The relay's own id for each is configuration ({@code annualva.mail.templates}). A ticket that mails a new kind adds
 * it here, to its migration's {@code mail_outbox_template_check} and to the configuration of every profile.
 */
enum MailTemplate {
    /** Returned for Rework, with what is Disputed (ADR 0043 item 6). */
    RETURNED_FOR_REWORK(MailValues.BASE),
    /** A window passed: the person whose step it is. */
    OVERDUE(MailValues.BASE),
    /** The System Register moved or transferred the System. */
    REGISTER_CHANGE(MailValues.WITH_CHANGE_KIND);

    private final Set<String> keys;

    MailTemplate(Set<String> keys) {
        this.keys = keys;
    }

    /** The value keys the template accepts; any other is outside the allow-list. */
    Set<String> keys() {
        return keys;
    }

    /**
     * The template a notification is mailed with, or none when it is in-app only. The required messages are the spec's
     * (Notifications): deadline reminders (ticket 38), Overdue, Return for Rework and Disputed, and Register changes. A
     * submit and a pass to endorsement are in-app only (provisional, ADR 0044 item 2): the reminders mail the Cyber
     * Manager and the VA POCs before their windows end.
     */
    static Optional<MailTemplate> of(NoticeAction action) {
        return switch (action) {
            case REWORK -> Optional.of(RETURNED_FOR_REWORK);
            case SUBMIT_OVERDUE, REVIEW_OVERDUE, ENDORSE_OVERDUE -> Optional.of(OVERDUE);
            case SYSTEM_MOVED, SYSTEM_TRANSFERRED_TO_YOU, SYSTEM_TRANSFERRED_FROM_YOU -> Optional.of(REGISTER_CHANGE);
            case REVIEW, ENDORSE -> Optional.empty();
        };
    }
}
