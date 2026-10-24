package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Stories 92 and 69 (ticket 36): the workflow's moves leave a notification for whoever acts next, after the move commits,
 * and every person reads their own in the app. Q3 of VA Cycle 2027 starts on Monday 2 August 2027: its Submission Window
 * ends on 15 August, its Review Window on 29 August and the Quarter on 31 October.
 */
class NotificationTest extends NotificationSeam1 {

    @Test
    void aSubmitNotifiesTheSystemsCyberManagerWithTheSystemStateActionDueDateAndLinkOnly() {
        Draft draft = inReview();
        String sub = draft.submissionId();

        JsonNode item = only(signedInAs(CM), "REVIEW");

        assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(FIELDS);
        assertThat(item.get("systemId").asString()).isEqualTo(draft.systemId());
        assertThat(item.get("systemCode").asString()).isEqualTo("SYS-F1");
        assertThat(item.get("systemName").asString()).isEqualTo("File one");
        assertThat(item.get("submissionId").asString()).isEqualTo(sub);
        assertThat(item.get("state").asString()).isEqualTo("IN_REVIEW");
        assertThat(item.get("message").asString()).isEqualTo("Review the VA Submission.");
        assertThat(item.get("dueOn").asString()).as("the Review Window's last day").isEqualTo("2027-08-29");
        assertThat(item.get("link").asString()).isEqualTo("/cyber-manager/submissions/" + sub);
        assertThat(item.get("createdAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(item.get("readAt").isNull()).isTrue();
        assertThat(item.get("handledBy").isNull()).isTrue();
        assertThat(notifications(draft.smo())).as("the SMO/Tech Lead acted; nothing waits on them").isEmpty();
        assertThat(notifications(signedInAs(CM_OTHER))).as("another System's Cyber Manager").isEmpty();
    }

    @Test
    void aReturnForReworkTellsTheSmoToReworkWhatIsDisputed() {
        Draft draft = inReview();
        String sub = draft.submissionId();
        ApiClient cm = signedInAs(CM);
        List<String> ids = findingIds(cm, sub);
        reviewed(cm, sub, ids.get(0), DISPUTED, "Show the upgrade.");
        clock.advance(Duration.ofMinutes(5));

        returned(cm, sub, "Two items need another look.");

        JsonNode item = only(draft.smo(), "REWORK");
        assertThat(item.get("state").asString()).isEqualTo(RETURNED_FOR_REWORK);
        assertThat(item.get("message").asString())
                .isEqualTo("Rework what is Disputed and submit the VA Submission again.");
        assertThat(item.get("dueOn").asString()).as("Returned for Rework waits on the Review Window")
                .isEqualTo("2027-08-29");
        assertThat(item.get("link").asString()).isEqualTo("/smo/submissions/" + sub + "/rework");
        assertThat(item.get("createdAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(actions(cm)).as("the Cyber Manager's own item stays in their list").containsExactly("REVIEW");
    }

    @Test
    void aPassToEndorsementTellsBothVaPocsAndAnEndorsementByOneClearsItForBothWithTheHandlersName() {
        Draft draft = awaitingEndorsement();
        String sub = draft.submissionId();
        ApiClient one = vaPoc();
        ApiClient two = otherVaPoc();
        JsonNode before = only(two, "ENDORSE");
        assertThat(only(one, "ENDORSE").get("id").asString()).as("one row each").isNotEqualTo(before.get("id").asString());
        assertThat(before.get("state").asString()).isEqualTo(AWAITING_ENDORSEMENT);
        assertThat(before.get("dueOn").asString()).as("the Quarter's last day").isEqualTo("2027-10-31");
        assertThat(before.get("link").asString()).isEqualTo("/va-poc/submissions/" + sub);
        assertThat(before.get("handledBy").isNull()).isTrue();
        clock.advance(Duration.ofMinutes(1));

        endorsed(one, sub);

        JsonNode sibling = only(two, "ENDORSE");
        assertThat(sibling.get("handledBy").asString()).isEqualTo("POC One");
        assertThat(sibling.get("handledAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(sibling.get("readAt").isNull()).as("cleared is not read").isTrue();
        assertThat(only(one, "ENDORSE").get("handledBy").asString()).isEqualTo("POC One");
        assertThat(notifications(draft.smo())).as("Endorsed is an FYI for the weekly digest (Release 2)").isEmpty();
    }

    @Test
    void aRefusedMoveTellsNobody() {
        Draft draft = inReview();
        ApiClient cm = signedInAs(CM);

        assertThat(passToEndorsement(cm, draft.submissionId()).status()).as("nothing is confirmed yet").isEqualTo(409);

        assertThat(notifications(vaPoc())).isEmpty();
        assertThat(notifications(otherVaPoc())).isEmpty();
    }

    @Test
    void aPersonMarksTheirOwnNotificationReadOnceAndAnotherPersonsIsNotFound() {
        Draft draft = inReview();
        ApiClient cm = signedInAs(CM);
        String id = only(cm, "REVIEW").get("id").asString();
        clock.advance(Duration.ofMinutes(1));
        String firstRead = clock.instant().toString();

        var marked = markRead(cm, id);
        clock.advance(Duration.ofMinutes(1));
        var again = markRead(cm, id);
        var someoneElse = markRead(draft.smo(), id);

        assertThat(marked.status()).as(marked.body()).isEqualTo(200);
        assertThat(ApiClient.json(marked).get("readAt").asString()).isEqualTo(firstRead);
        assertThat(ApiClient.json(again).get("readAt").asString()).as("the first read is kept").isEqualTo(firstRead);
        assertThat(only(cm, "REVIEW").get("readAt").asString()).isEqualTo(firstRead);
        assertThat(someoneElse.status()).as("the same answer as one that does not exist").isEqualTo(404);
        assertThat(markRead(cm, UUID.randomUUID().toString()).status()).isEqualTo(404);
    }

    @Test
    void everyRoleReadsItsOwnListAndNobodyElsesWithoutSigningIn() {
        inReview();
        arrange.grant("AUDITOR", "notice.auditor@ci.annualva.test", null, "GRANT", null);

        var auditor = signedInAs("notice.auditor@ci.annualva.test").get(NOTIFICATIONS);
        var anonymous = api.get(NOTIFICATIONS);

        assertThat(auditor.status()).as(auditor.body()).isEqualTo(200);
        assertThat(ApiClient.json(auditor).get("total").asLong()).isZero();
        assertThat(anonymous.status()).isEqualTo(401);
        JsonNode page = ApiClient.json(signedInAs(CM).get(NOTIFICATIONS + "?page=0&size=1"));
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(1);
    }
}
