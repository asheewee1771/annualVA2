package sg.annualva.adapters.local;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.MailRelay;

/**
 * The mail relay of the {@code dev-local} profile: nothing is sent; each message is one {@code Event=email} line in the
 * log, as mock-MCNS writes one, so {@code grep "Event=email"} shows what would have been mailed. The line carries the
 * template and the System, state, action, due date and link, and never the address or any other value (the application
 * already limits the values to these, and this lists them again so a new value is not logged unseen). An address whose
 * local part ends in {@code .bounce} is refused, as the notification service refuses one it cannot deliver to, so SMO
 * Unreachable can be shown.
 */
@Component
@Profile("dev-local")
class LocalMailRelay implements MailRelay {

    private static final Logger log = LoggerFactory.getLogger(LocalMailRelay.class);
    private static final String BOUNCE_SUFFIX = ".bounce";
    private static final List<String> LOGGED_VALUES = List.of("system_id", "system_name", "state", "action", "due_date",
            "link", "change_kind");

    private final AtomicLong counter = new AtomicLong();

    @Override
    public Receipt send(String recipient, String templateId, Map<String, String> values) {
        if (bounces(recipient)) {
            return new AddressRejected();
        }
        var line = log.atInfo().setMessage("Event=email").addKeyValue("event.action", "email")
                .addKeyValue("labels.template", templateId);
        for (String key : LOGGED_VALUES) {
            String value = values.get(key);
            if (value != null) {
                line = line.addKeyValue("labels." + key, value);
            }
        }
        line.log();
        return new Accepted("local-mail-" + counter.incrementAndGet());
    }

    private static boolean bounces(String recipient) {
        int at = recipient.lastIndexOf('@');
        String local = at < 0 ? recipient : recipient.substring(0, at);
        return local.toLowerCase(Locale.ROOT).endsWith(BOUNCE_SUFFIX);
    }
}
