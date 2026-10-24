package sg.annualva.adapters.pending;

import java.util.Map;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.MailRelay;

/**
 * The mail relay until the production adapter exists (ticket 51 builds the notification service's client). Every call
 * fails as a relay that cannot be reached, so a queued mail is tried and, after its last attempt, given up on and kept in
 * the outbox; nothing is sent anywhere else. The Seam 1 harness replaces it with a capturing fake.
 */
@Component
class UnwiredMailRelay implements MailRelay {

    @Override
    public Receipt send(String recipient, String templateId, Map<String, String> values) {
        throw new BoundaryFailureException("The mail relay is not wired");
    }
}
