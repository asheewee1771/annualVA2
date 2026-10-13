package sg.annualva.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * IM8 ac-1 and as-7 for the VA Submission: only the System's own SMO/Tech Lead may start it, and only people who may see
 * the System may read it. A Cyber Manager, VA POC (who is not the System's SMO), Auditor or person with no role is
 * refused with 403 where the role alone decides; a System or VA Submission that is another person's answers exactly like
 * one that does not exist (ADR 0013).
 */
class SubmissionAccessTest extends SubmissionSeam1 {

    private static final String SMO_C = "sub.smo.c@ci.annualva.test";

    private void registerSystems() {
        registered(
                List.of(listed("SYS-X1", "Access one", SMO_A, CM, "Q3"),
                        listed("SYS-X2", "Access two", SMO_B, CM_OTHER, "Q3")),
                List.of());
    }

    private static void assertSameAnswerAsMissing(ApiClient.Response refused, ApiClient.Response missing) {
        assertThat(refused.status()).isEqualTo(404);
        JsonNode refusedBody = ApiClient.json(refused);
        JsonNode missingBody = ApiClient.json(missing);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(refusedBody.get("type").asString()).isEqualTo("https://annualva/problems/not-found");
        assertThat(refusedBody.get("type")).isEqualTo(missingBody.get("type"));
        assertThat(refusedBody.get("title")).isEqualTo(missingBody.get("title"));
        assertThat(refusedBody.get("detail")).isEqualTo(missingBody.get("detail"));
        assertThat(refusedBody.propertyNames()).containsExactlyInAnyOrderElementsOf(missingBody.propertyNames());
    }

    @Test
    void aCyberManagerAndAVaPocWhoIsNotTheSmoCannotStartAndLearnNothingOfTheSystem() {
        registerSystems();
        String system = idOf("SYS-X1");

        var manager = start(signedInAs(CM), system);
        var poc = start(vaPoc(), system);

        assertThat(manager.status()).isEqualTo(403);
        assertThat(problemType(manager)).isEqualTo("https://annualva/problems/forbidden");
        assertThat(poc.status()).isEqualTo(403);
        assertThat(manager.body() + poc.body()).doesNotContain("SYS-X1").doesNotContain("Access one");
    }

    @Test
    void anAuditorAnAnonymousCallerAndAPersonWithNoRoleCannotStart() {
        registerSystems();
        String system = idOf("SYS-X1");
        ApiClient auditor = signedInAs("sub.auditor@ci.annualva.test");
        arrange.grant("AUDITOR", "sub.auditor@ci.annualva.test", null, "GRANT", "2027-12-31T00:00:00Z");

        assertThat(start(auditor, system).status()).isEqualTo(403);
        assertThat(start(signedInAs("sub.nobody@ci.annualva.test"), system).status()).isEqualTo(403);
        assertThat(start(anotherBrowser(), system).status()).isIn(401, 403);
    }

    @Test
    void aStartWithoutTheCsrfTokenIsRefusedAndStartsNothing() {
        registerSystems();
        ApiClient noToken = anotherBrowser();
        SignIn.signedIn(noToken, issuer, fresh(TestIdentity.of("af-r-" + Math.abs(SMO_A.hashCode()), SMO_A, "Person")));

        var response = start(noToken, idOf("SYS-X1"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(problemType(response)).isEqualTo("https://annualva/problems/csrf-rejected");
        assertThat(ApiClient.json(noToken.get("/api/dashboards/smo-inbox")).get("systems").get(0).get("state").asString())
                .isEqualTo("NOT_STARTED");
    }

    @Test
    void anotherSmoStartingASystemThatIsNotTheirsGetsTheSameAnswerAsForOneThatDoesNotExist() {
        registerSystems();
        ApiClient other = signedInAs(SMO_B);

        var notTheirs = start(other, idOf("SYS-X1"));
        var missing = start(other, UUID.randomUUID().toString());

        assertSameAnswerAsMissing(notTheirs, missing);
        ApiClient owner = signedInAs(SMO_A);
        assertThat(ApiClient.json(owner.get("/api/dashboards/smo-inbox")).get("systems").get(0).get("state").asString())
                .as("nothing was started for the owner").isEqualTo("NOT_STARTED");
    }

    @Test
    void anSmoWhoLostTheSystemToTheRegisterCannotStartIt() {
        registered(listed("SYS-X3", "Access three", SMO_C, CM, "Q3"));
        ApiClient former = signedInAs(SMO_C);
        String system = idOf("SYS-X3");
        registered(listed("SYS-X3", "Access three", SMO_A, CM, "Q3"));

        assertThat(start(former, system).status()).as("the former SMO holds no role any more").isEqualTo(403);
        assertThat(start(signedInAs(SMO_A), system).status()).isEqualTo(201);
    }

    @Test
    void theSmoTheirCyberManagerAndAnyVaPocReadTheVaSubmissionWithWhoActedAtWhichStep() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        JsonNode submission = started(smo, idOf("SYS-X1"));
        String id = submission.get("id").asString();

        for (ApiClient reader : List.of(smo, signedInAs(CM), vaPoc(), otherVaPoc())) {
            var response = read(reader, id);
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            JsonNode read = ApiClient.json(response);
            assertThat(read.get("state").asString()).isEqualTo("DRAFT");
            assertThat(read.get("submitDue").asString()).isEqualTo("2027-08-15");
            assertThat(read.get("overdue").asBoolean()).isFalse();
            assertThat(read.get("acted")).hasSize(1);
            assertThat(read.get("acted").get(0).get("step").asString()).isEqualTo("SUBMIT");
            assertThat(read.get("acted").get(0).get("userId").asString()).isEqualTo(submission.get("acted").get(0).get("userId").asString());
        }
    }

    @Test
    void aVaSubmissionThatIsAnotherPersonsAnswersLikeOneThatDoesNotExist() {
        registerSystems();
        String id = started(signedInAs(SMO_A), idOf("SYS-X1")).get("id").asString();
        String missing = UUID.randomUUID().toString();

        for (String stranger : List.of(SMO_B, CM_OTHER)) {
            ApiClient reader = signedInAs(stranger);
            assertSameAnswerAsMissing(read(reader, id), read(reader, missing));
        }
    }

    @Test
    void anAuditorAnAnonymousCallerAndAPersonWithNoRoleCannotRead() {
        registerSystems();
        String id = started(signedInAs(SMO_A), idOf("SYS-X1")).get("id").asString();
        ApiClient auditor = signedInAs("sub.auditor@ci.annualva.test");
        arrange.grant("AUDITOR", "sub.auditor@ci.annualva.test", null, "GRANT", "2027-12-31T00:00:00Z");

        assertThat(read(auditor, id).status()).isEqualTo(403);
        assertThat(read(signedInAs("sub.nobody@ci.annualva.test"), id).status()).isEqualTo(403);
        var anonymous = read(anotherBrowser(), id);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(problemType(anonymous)).isEqualTo("https://annualva/problems/unauthenticated");
    }

    @Test
    void anIdThatIsNotAnIdIsABadRequestWhoseSentenceDoesNotEchoIt() {
        registerSystems();

        var response = read(signedInAs(SMO_A), "CVE-9999-SENTINEL");

        assertThat(response.status()).isEqualTo(400);
        JsonNode problem = ApiClient.json(response);
        assertThat(problem.get("title").asString() + problem.get("detail").asString()).doesNotContain("SENTINEL");
    }

    @Test
    void theVaSubmissionCannotBeChangedThroughItsAddress() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        String id = started(smo, idOf("SYS-X1")).get("id").asString();

        assertThat(smo.postJson(SUBMISSIONS + "/" + id, "{\"state\":\"ENDORSED\"}").status()).isIn(403, 404, 405);
        assertThat(ApiClient.json(read(smo, id)).get("state").asString()).isEqualTo("DRAFT");
    }
}
