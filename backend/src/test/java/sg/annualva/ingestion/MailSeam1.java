package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.StreamSupport;
import sg.annualva.harness.CapturingMailRelay.Sent;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 37 share (stories 91 and 94): the mail the capturing relay accepted, and the outbox the
 * dispatcher works through. The dispatcher runs one pass at a time, by hand, because scheduling is off in tests. The
 * template ids are the {@code ci} profile's.
 */
abstract class MailSeam1 extends NotificationSeam1 {

    static final String APP_URL = "https://annualva.ci.test";
    static final String REWORK_TEMPLATE = "ci-returned-for-rework";
    static final String OVERDUE_TEMPLATE = "ci-overdue";
    static final String REGISTER_CHANGE_TEMPLATE = "ci-register-change";

    /** The one outbox row of this address. */
    protected JsonNode onlyMailOf(String email) {
        List<JsonNode> rows = outboxOf(email);
        assertThat(rows).as("one mail queued for %s", email).hasSize(1);
        return rows.getFirst();
    }

    /** One pass of the dispatcher. */
    protected void dispatched() {
        arrange.mailDispatch();
    }

    /** The mail the relay accepted for this address, in order. */
    protected List<Sent> mailTo(String email) {
        return mailRelay.sent().stream().filter(sent -> sent.recipient().equalsIgnoreCase(email)).toList();
    }

    /** The outbox rows of this address, oldest first. */
    protected List<JsonNode> outboxOf(String email) {
        return StreamSupport.stream(arrange.mailOutbox().spliterator(), false)
                .filter(row -> row.get("recipient").asString().equalsIgnoreCase(email)).toList();
    }
}
