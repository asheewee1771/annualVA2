package sg.annualva.boundary;

import java.util.Map;

/**
 * The mail-relay port: the organisation's notification service, which sends an email from an onboarded template (ADR
 * 0003, assumption A-9). The application hands it a recipient, the relay's own id of a template and the values the
 * template fills in; it never hands over a subject or a body, so the template, not the application, decides the wording.
 * The values have already passed the application's allow-list (ADR 0045). The production adapter is the notification
 * service's client (ticket 51); the Seam 1 harness fakes it with a relay that captures every message.
 *
 * <p>A recipient address is never logged by an adapter or by its caller (story 91, lm-19). Every method should throw
 * {@link BoundaryFailureException} when the relay cannot be reached or refuses the call for a reason that may pass; the
 * caller treats any other runtime exception the same way and tries again later. A call must end well within the outbox's
 * lease ({@code annualva.mail.lease}, five minutes), so an adapter sets its own connect and read timeouts below it;
 * otherwise another pass may claim the mail again and send it twice.
 */
public interface MailRelay {

    /** What the relay said about one message it was handed. */
    sealed interface Receipt {}

    /**
     * The relay took the message.
     *
     * @param messageId the relay's id of the message, kept so a later receipt can be matched to it
     */
    record Accepted(String messageId) implements Receipt {}

    /** The relay refused the recipient's address: sending the same message again cannot succeed (ticket 39). */
    record AddressRejected() implements Receipt {}

    /**
     * Hands one message to the relay.
     *
     * @param recipient the address it is for; never logged
     * @param templateId the relay's id of the onboarded template
     * @param values the template's values by key
     */
    Receipt send(String recipient, String templateId, Map<String, String> values);
}
