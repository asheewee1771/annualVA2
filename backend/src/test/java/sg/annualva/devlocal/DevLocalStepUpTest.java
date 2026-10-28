package sg.annualva.devlocal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.AuditEntries;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 63, ADR 0058: under dev-local a step-up is a local confirmation. It means what it means with the identity
 * provider: it makes the session's {@code auth_time} fresh, for the same person and the intent that was started, and a
 * session that is not fresh is still refused. It never extends the session.
 */
class DevLocalStepUpTest extends DevLocalSeam1 {

    private static final String INTENT = "probe-sensitive";
    private static final String START = "/auth/step-up?intent=" + INTENT;

    private static ApiClient.Response sensitive(ApiClient browser) {
        return browser.post("/api/probe/sensitive");
    }

    private static ApiClient.Response confirm(ApiClient browser, String intent) {
        return browser.postJson("/api/dev/step-up", "{\"intent\":\"" + intent + "\"}");
    }

    private ApiClient staleSession() {
        ApiClient poc = signedInAs(POC_1);
        clock.advance(Duration.ofMinutes(6));
        assertThat(sensitive(poc).status()).as("stale").isEqualTo(403);
        return poc;
    }

    @Test
    void aSensitiveActionRunsWhileTheSignInIsFreshAndIsRefusedOnceItIsOlderThanFiveMinutes() {
        ApiClient poc = signedInAs(POC_1);

        assertThat(sensitive(poc).status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(5));
        assertThat(sensitive(poc).status()).as("exactly at the window").isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        var refused = sensitive(poc);

        assertThat(refused.status()).isEqualTo(403);
        JsonNode problem = ApiClient.json(refused);
        assertThat(problem.get("type").asString()).isEqualTo("https://annualva/problems/step-up-required");
        assertThat(problem.get("stepUpPath").asString()).isEqualTo(START);
    }

    @Test
    void startingAStepUpSendsTheBrowserToTheLocalConfirmationPageWithTheIntentAndReturnPath() {
        ApiClient poc = staleSession();

        var started = poc.get(START + "&returnTo=/va-poc/register");

        assertThat(started.status()).isEqualTo(302);
        assertThat(started.header("location")).endsWith("/dev-step-up?intent=probe-sensitive&returnTo=%2Fva-poc%2Fregister");
    }

    @Test
    void confirmingTheStartedStepUpMakesTheSessionFreshAgainForTheSamePerson() {
        ApiClient poc = staleSession();
        String before = session(poc).get("uuid").asString();
        poc.get(START + "&returnTo=/va-poc");

        var confirmed = confirm(poc, INTENT);

        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(ApiClient.json(confirmed).get("returnTo").asString()).isEqualTo("/va-poc");
        assertThat(sensitive(poc).status()).isEqualTo(200);
        assertThat(session(poc).get("uuid").asString()).as("the same person").isEqualTo(before);
        clock.advance(Duration.ofMinutes(6));
        assertThat(sensitive(poc).status()).as("fresh for the window only").isEqualTo(403);
    }

    @Test
    void aStepUpNeverExtendsTheSession() {
        ApiClient poc = signedInAs(POC_1);
        String endsAt = session(poc).get("absoluteExpiresAt").asString();
        clock.advance(Duration.ofMinutes(6));
        poc.get(START);

        assertThat(confirm(poc, INTENT).status()).isEqualTo(200);

        assertThat(session(poc).get("absoluteExpiresAt").asString()).isEqualTo(endsAt);
    }

    @Test
    void confirmingWithoutHavingStartedAStepUpIsRefusedAndTheSessionStaysStale() {
        ApiClient poc = staleSession();

        var refused = confirm(poc, INTENT);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(ApiClient.json(refused).get("type").asString()).isEqualTo("https://annualva/problems/step-up-not-started");
        assertThat(sensitive(poc).status()).isEqualTo(403);
    }

    @Test
    void aConfirmationIsBoundToTheIntentThatWasStarted() {
        ApiClient poc = staleSession();
        poc.get(START);

        var refused = confirm(poc, "disposal-approve");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(sensitive(poc).status()).isEqualTo(403);
        assertThat(confirm(poc, INTENT).status()).as("the started step-up is still waiting").isEqualTo(200);
    }

    @Test
    void aConfirmationIsUsedOnce() {
        ApiClient poc = staleSession();
        poc.get(START);
        assertThat(confirm(poc, INTENT).status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(6));

        assertThat(confirm(poc, INTENT).status()).isEqualTo(409);
        assertThat(sensitive(poc).status()).isEqualTo(403);
    }

    @Test
    void aStepUpStartedLongAgoIsNotConfirmed() {
        ApiClient poc = staleSession();
        poc.get(START);
        clock.advance(Duration.ofMinutes(6));

        assertThat(confirm(poc, INTENT).status()).isEqualTo(409);
        assertThat(sensitive(poc).status()).isEqualTo(403);
    }

    @Test
    void aStepUpStartedByOnePersonCannotBeConfirmedBySomeoneElse() {
        ApiClient poc = staleSession();
        poc.get(START);
        ApiClient other = signedInAs(POC_2);

        assertThat(confirm(other, INTENT).status()).as("the other session has started nothing").isEqualTo(409);
        assertThat(confirm(poc, INTENT).status()).isEqualTo(200);
    }

    @Test
    void aReturnPathThatLeavesTheSiteIsReplacedByTheRoot() {
        ApiClient poc = staleSession();
        var started = poc.get(START + "&returnTo=//evil.example/x");

        assertThat(started.header("location")).endsWith("/dev-step-up?intent=probe-sensitive&returnTo=%2F");
        assertThat(ApiClient.json(confirm(poc, INTENT)).get("returnTo").asString()).isEqualTo("/");
    }

    @Test
    void anIntentThatIsNotACodeIsRefused() {
        ApiClient poc = signedInAs(POC_1);

        assertThat(poc.get("/auth/step-up?intent=Not%20a%20code").status()).isEqualTo(400);
        assertThat(confirm(poc, "Not a code").status()).isEqualTo(400);
    }

    @Test
    void nobodyWhoIsSignedOutCanStartOrConfirmAStepUp() {
        ApiClient anonymous = browser().withCsrf();

        assertThat(anonymous.get(START).status()).isEqualTo(401);
        assertThat(confirm(anonymous, INTENT).status()).isEqualTo(401);
    }

    @Test
    void eachStepOfAStepUpIsAuditedWithTheUserIdAndTheIntent() {
        ApiClient reader = signedInAs(POC_2);
        ApiClient poc = staleSession();
        String pocId = session(poc).get("uuid").asString();
        List<String> actions = List.of("STEP_UP_STARTED", "STEP_UP_OK", "STEP_UP_FAILED");
        List<Integer> before = actions.stream()
                .map(action -> AuditEntries.all(reader, "action=" + action + "&actor=" + pocId).size()).toList();

        poc.get(START);
        confirm(poc, "disposal-approve");
        assertThat(confirm(poc, INTENT).status()).isEqualTo(200);

        for (int i = 0; i < actions.size(); i++) {
            List<JsonNode> entries = AuditEntries.all(reader, "action=" + actions.get(i) + "&actor=" + pocId);
            assertThat(entries).as(actions.get(i)).hasSize(before.get(i) + 1);
            assertThat(entries.toString())
                    .contains(actions.get(i).equals("STEP_UP_FAILED") ? "disposal-approve" : INTENT);
        }
    }

    @Test
    void aRealSensitiveActionIsSatisfiedByTheLocalStepUp() {
        registerApplied("DEV-1|Payroll|smo.a@dev.annualva.test|cm.a@dev.annualva.test|Q3");
        ApiClient poc = signedInAs(POC_1);
        var upload = poc.postFile("/api/system-register/uploads", "file", "register.xlsx", XLSX,
                sg.annualva.harness.RegisterXlsx.register()
                        .row("DEV-2", "Billing", "smo.b@dev.annualva.test", "cm.b@dev.annualva.test", "Q3").build());
        String id = ApiClient.json(upload).get("id").asString();
        clock.advance(Duration.ofMinutes(10));

        var refused = poc.post("/api/system-register/previews/" + id + "/apply");
        assertThat(refused.status()).isEqualTo(403);
        String path = ApiClient.json(refused).get("stepUpPath").asString();
        poc.get(path);
        assertThat(confirm(poc, "register-apply").status()).isEqualTo(200);

        assertThat(poc.post("/api/system-register/previews/" + id + "/apply").status()).isEqualTo(200);
    }
}
