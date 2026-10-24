package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 36 share (stories 92 and 69): the reads and the mark-as-read call a person's Notifications
 * screen makes, and the one notification a test looks for among them.
 */
abstract class NotificationSeam1 extends EndorseSeam1 {

    static final String NOTIFICATIONS = "/api/notifications";

    /** Every field a notification is answered with: the System, the state, the action, the due date and the link only. */
    static final List<String> FIELDS = List.of("id", "systemId", "systemCode", "systemName", "submissionId", "state",
            "action", "message", "dueOn", "link", "createdAt", "readAt", "handledBy", "handledAt");

    /** This person's notifications, newest first, as their screen reads them (the first page). */
    protected static JsonNode notifications(ApiClient client) {
        var response = client.get(NOTIFICATIONS);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    /** The actions of this person's notifications, newest first. */
    protected static List<String> actions(ApiClient client) {
        List<String> actions = new ArrayList<>();
        notifications(client).forEach(item -> actions.add(item.get("action").asString()));
        return actions;
    }

    /** The one notification of this person with this action. */
    protected static JsonNode only(ApiClient client, String action) {
        List<JsonNode> found = new ArrayList<>();
        notifications(client).forEach(item -> {
            if (item.get("action").asString().equals(action)) {
                found.add(item);
            }
        });
        assertThat(found).as("one %s", action).hasSize(1);
        return found.getFirst();
    }

    protected static ApiClient.Response markRead(ApiClient client, String notificationId) {
        return client.post(NOTIFICATIONS + "/" + notificationId + "/read");
    }

    /**
     * Every identifying text of every Finding of the VA Submission: its key, identifier, package or rule and title, which
     * no notification and no mail may carry (story 91).
     */
    protected static List<String> findingDetail(ApiClient reader, String submissionId) {
        List<String> detail = new ArrayList<>();
        for (JsonNode finding : findings(reader, submissionId).get("items")) {
            for (String field : List.of("identityKey", "identifier", "packageOrRule", "title")) {
                JsonNode value = finding.get(field);
                if (value != null && !value.isNull()) {
                    detail.add(value.asString());
                }
            }
        }
        return detail;
    }
}
