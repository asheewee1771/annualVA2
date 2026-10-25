package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * IM8 ac-4 (ticket 47): the VA POCs review every role holder against the System Register at each Register upload and at
 * least every 3 months, see the roles that came back by themselves since the last review, and record each review. The
 * default clock is 09:00 on Monday 2 August 2027 in Singapore.
 */
class RoleHolderReviewTest extends RoleHolderSeam1 {

    private static final String REVIEWED = "ROLE_HOLDERS_REVIEWED";

    private static List<String> dueBecause(JsonNode review) {
        List<String> reasons = new ArrayList<>();
        review.get("dueBecause").forEach(reason -> reasons.add(reason.asString()));
        return reasons;
    }

    private static JsonNode completed(ApiClient poc) {
        var response = poc.post(HOLDERS + "/reviews");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response);
    }

    @Test
    void aVaPocListsEveryRoleHolderAgainstTheRegister() {
        twoSystems();
        signedInAs(SMO_A);

        JsonNode review = review(vaPoc());

        JsonNode smo = holder(review, "SMO", SMO_A, "SYS-HA");
        assertThat(smo.get("systemName").asString()).isEqualTo("Holder A");
        assertThat(smo.get("source").asString()).isEqualTo("REGISTER");
        assertThat(smo.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(smo.get("namedInRegister").asBoolean()).isTrue();
        assertThat(smo.get("signedIn").asBoolean()).isTrue();
        assertThat(smo.get("own").asBoolean()).isFalse();
        assertThat(smo.get("suspendable").asBoolean()).isTrue();
        assertThat(smo.get("restorable").asBoolean()).isFalse();
        assertThat(holder(review, "SMO", SMO_B, "SYS-HB").get("signedIn").asBoolean()).as("named, not signed in yet")
                .isFalse();
        holder(review, "CYBER_MANAGER", CM, "SYS-HA");
        holder(review, "CYBER_MANAGER", CM, "SYS-HB");
        JsonNode poc = holder(review, "VA_POC", POC_ONE, null);
        assertThat(poc.get("source").asString()).isEqualTo("CONFIG");
        assertThat(poc.get("namedInRegister").isNull()).as("not a Register role").isTrue();
        assertThat(poc.get("own").asBoolean()).isTrue();
        assertThat(poc.get("suspendable").asBoolean()).as("own, and not held for a System").isFalse();
        holder(review, "VA_POC", POC_TWO, null);
    }

    @Test
    void onlyAVaPocReadsOrRecordsTheReview() {
        twoSystems();
        ApiClient smo = signedInAs(SMO_A);

        assertThat(smo.get(HOLDERS).status()).isEqualTo(403);
        assertThat(smo.post(HOLDERS + "/reviews").status()).isEqualTo(403);
        assertThat(signedInAs(CM).get(HOLDERS).status()).isEqualTo(403);
    }

    @Test
    void aReviewIsDueUntilOneIsRecordedAndAgainThreeMonthsLater() {
        twoSystems();
        clock.set(Instant.parse("2030-01-02T01:00:00Z"));
        ApiClient poc = vaPoc();
        JsonNode before = review(poc);
        assertThat(before.get("reviewDue").asBoolean()).isTrue();
        assertThat(dueBecause(before)).containsExactly("never-reviewed");

        JsonNode recorded = completed(poc);

        assertThat(recorded.get("reviewedAt").asString()).isEqualTo("2030-01-02T01:00:00Z");
        assertThat(recorded.get("reviewedBy").asString()).isEqualTo(userId(poc));
        assertThat(recorded.get("holders").asInt()).as("every holder listed").isEqualTo(before.get("holders").size());
        JsonNode after = review(poc);
        assertThat(after.get("reviewDue").asBoolean()).isFalse();
        assertThat(dueBecause(after)).isEmpty();
        assertThat(after.get("lastReview").get("reviewedAt").asString()).isEqualTo("2030-01-02T01:00:00Z");

        clock.set(Instant.parse("2030-04-01T01:00:00Z"));
        assertThat(review(vaPoc()).get("reviewDue").asBoolean()).as("not yet 3 months").isFalse();
        clock.set(Instant.parse("2030-04-02T01:00:00Z"));
        JsonNode due = review(vaPoc());
        assertThat(due.get("reviewDue").asBoolean()).isTrue();
        assertThat(dueBecause(due)).containsExactly("three-months");
    }

    @Test
    void aRegisterApplyAfterTheLastReviewMakesAReviewDue() {
        twoSystems();
        clock.set(Instant.parse("2030-06-03T01:00:00Z"));
        completed(vaPoc());
        clock.advance(Duration.ofDays(1));

        registered(listed("SYS-HA", "Holder A", SMO_C, CM, "Q3"), listed("SYS-HB", "Holder B", SMO_B, CM, "Q4"));

        JsonNode review = review(vaPoc());
        assertThat(dueBecause(review)).containsExactly("register-applied");
        holder(review, "SMO", SMO_C, "SYS-HA");
        List<String> emails = new ArrayList<>();
        review.get("holders").forEach(holder -> emails.add(holder.get("email").asString()));
        assertThat(emails).as("SMO_A is no longer named").doesNotContain(SMO_A);
    }

    @Test
    void recordingAReviewIsAudited() {
        twoSystems();
        ApiClient poc = vaPoc();

        JsonNode recorded = completed(poc);

        var response = poc.get("/api/audit-entries?size=5&action=" + REVIEWED + "&actor=" + userId(poc));
        JsonNode entry = ApiClient.json(response).get("items").get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("detail").get("count").asInt()).isEqualTo(recorded.get("holders").asInt());
        assertThat(entry.get("detail").toString()).doesNotContain("@");
    }

    @Test
    void theRolesThatCameBackByThemselvesSinceTheLastReviewAreListed() {
        twoSystems();
        clock.set(Instant.parse("2030-09-02T01:00:00Z"));
        ApiClient poc = vaPoc();
        String smoGrant = grantOf(review(poc), "SMO", SMO_A, "SYS-HA");
        arrange.lapse(smoGrant);
        completed(poc);
        clock.advance(Duration.ofHours(1));

        twoSystems();

        JsonNode review = review(vaPoc());
        JsonNode returned = review.get("automaticReturns");
        assertThat(returned).hasSize(1);
        assertThat(returned.get(0).get("id").asString()).isEqualTo(smoGrant);
        assertThat(returned.get(0).get("returnTrigger").asString()).isEqualTo("REGISTER");
        assertThat(returned.get(0).get("returnedAt").asString()).isEqualTo("2030-09-02T02:00:00Z");
        assertThat(holder(review, "SMO", SMO_A, "SYS-HA").get("state").asString()).isEqualTo("ACTIVE");

        completed(vaPoc());
        assertThat(review(vaPoc()).get("automaticReturns")).as("reviewed since").isEmpty();
    }
}
