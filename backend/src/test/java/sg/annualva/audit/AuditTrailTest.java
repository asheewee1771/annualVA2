package sg.annualva.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Story 84, IM8 lm-4 and lm-15: every sign-in, sign-out, failed sign-in, step-up and refused request is an audit entry
 * with the actor, their roles, the action, the outcome, a correlation id and only allow-listed detail.
 */
class AuditTrailTest extends AuditSeam1 {

    private static final String START = "/auth/step-up?intent=probe-sensitive";

    @Test
    void signInAndSignOutAreEntriesNamingTheUserAndNoRoleWhenThereIsNone() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-a501", "audit.person@ci.annualva.test", "Audit Person"));
        String userId = userId(api);
        assertThat(api.withCsrf().post("/api/logout").status()).isEqualTo(204);

        List<JsonNode> mine = entries(reader(), "actor=" + userId);

        assertThat(actions(mine)).as("newest first").containsExactly("SIGN_OUT", "SIGN_IN");
        for (JsonNode entry : mine) {
            assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(entry.get("actorId").asString()).isEqualTo(userId);
            assertThat(entry.get("actorRole").isNull()).as("this person holds no role").isTrue();
            assertThat(Instant.parse(entry.get("occurredAt").asString())).isEqualTo(clock.instant());
            assertThat(entry.get("correlationId").asString()).isNotBlank();
            assertThat(entry.get("systemId").isNull() && entry.get("submissionId").isNull()
                    && entry.get("findingId").isNull()).isTrue();
            assertThat(entry.get("detail").size()).isZero();
        }
    }

    @Test
    void aSignInRecordsTheRolesHeldAtTheTime() {
        String system = arrange.system();
        arrange.grant("CYBER_MANAGER", "audit.manager@ci.annualva.test", system);
        arrange.grant("SMO", "audit.manager@ci.annualva.test", arrange.system());
        SignIn.signedIn(api, issuer, TestIdentity.of("af-a502", "audit.manager@ci.annualva.test", "Audit Manager"));

        List<JsonNode> mine = entries(reader(), "actor=" + userId(api) + "&action=SIGN_IN");

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("actorRole").asString()).isEqualTo("CYBER_MANAGER,SMO");
    }

    @Test
    void aRefusedSignInIsAnEntryWithAFixedReasonAndNoActor() {
        SignIn.through(api, issuer, TestIdentity.of("af-a503", "refused.person@ci.annualva.test", "Refused Person")
                .withAmr(List.of("pwd")));

        List<JsonNode> failed = entries(reader(), "action=SIGN_IN_FAILED");

        assertThat(failed).hasSize(1);
        JsonNode entry = failed.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entry.get("actorId").isNull()).as("a refused sign-in has not proved who the person is").isTrue();
        assertThat(entry.get("detail").get("reason").asString()).isEqualTo("mfa-not-evidenced");
        assertThat(entry.toString()).doesNotContain("refused.person").doesNotContain("Refused Person");
    }

    @Test
    void aSignInThatFailedAtTheIdentityProviderIsAFailedEntry() {
        SignIn.through(api, issuer, TestIdentity.of("af-a504", "failed.person@ci.annualva.test", "Failed Person")
                .withTamperedNonce());

        List<JsonNode> failed = entries(reader(), "action=SIGN_IN_FAILED");

        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).get("outcome").asString()).isEqualTo("FAILED");
        assertThat(failed.get(0).get("detail").get("reason").asString()).isEqualTo("provider-error");
        assertThat(failed.get(0).get("detail").get("error_code").asString()).isEqualTo("invalid_nonce");
    }

    @Test
    void aRequestWithNoSessionIsAnUnauthenticatedRefusal() {
        assertThat(api.get("/api/anything").status()).isEqualTo(401);

        List<JsonNode> refused = entries(reader(), "action=REQUEST_REFUSED");

        assertThat(refused).hasSize(1);
        JsonNode entry = refused.get(0);
        assertThat(entry.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(entry.get("actorId").isNull()).isTrue();
        assertThat(entry.get("detail").get("status").asInt()).isEqualTo(401);
        assertThat(entry.get("detail").get("reason").asString()).isEqualTo("unauthenticated");
        assertThat(entry.get("correlationId").asString()).isNotBlank();
    }

    @Test
    void aRoleRuleThatSaysNoIsAForbiddenRefusalNamingThePersonAndTheirRoles() {
        arrange.grant("CYBER_MANAGER", "refused.manager@ci.annualva.test", arrange.system());
        SignIn.signedIn(api, issuer, TestIdentity.of("af-a505", "refused.manager@ci.annualva.test", "Refused Manager"));

        var response = api.get("/api/probe/va-poc");
        assertThat(response.status()).isEqualTo(403);

        List<JsonNode> refused = entries(reader(), "action=REQUEST_REFUSED&actor=" + userId(api));
        assertThat(refused).hasSize(1);
        assertThat(refused.get(0).get("actorRole").asString()).isEqualTo("CYBER_MANAGER");
        assertThat(refused.get(0).get("detail").get("status").asInt()).isEqualTo(403);
        assertThat(refused.get(0).get("detail").get("reason").asString()).isEqualTo("forbidden");
        assertThat(refused.get(0).get("correlationId").asString())
                .as("the entry carries the correlation id the caller was given")
                .isEqualTo(response.header("X-Correlation-Id"));
    }

    @Test
    void aMissingCsrfTokenIsARefusalWithTheSignedInPerson() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-a506", "csrf.person@ci.annualva.test", "Csrf Person"));

        assertThat(api.post("/api/probe/sensitive").status()).isEqualTo(403);

        List<JsonNode> refused = entries(reader(), "action=REQUEST_REFUSED&actor=" + userId(api));
        assertThat(refused).hasSize(1);
        assertThat(refused.get(0).get("detail").get("reason").asString()).isEqualTo("csrf-rejected");
    }

    @Test
    void aSensitiveActionWithoutAFreshSignInIsARefusalNamingTheIntent() {
        var identity = TestIdentity.of("af-a507", "stepup.required@ci.annualva.test", "Step Up Required")
                .withAuthTime(clock.instant());
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(6));

        assertThat(api.post("/api/probe/sensitive").status()).isEqualTo(403);

        List<JsonNode> refused = entries(reader(), "action=REQUEST_REFUSED&actor=" + userId(api));
        assertThat(refused).hasSize(1);
        JsonNode detail = refused.get(0).get("detail");
        assertThat(detail.get("reason").asString()).isEqualTo("step-up-required");
        assertThat(detail.get("intent").asString()).isEqualTo("probe-sensitive");
        assertThat(detail.get("status").asInt()).isEqualTo(403);
    }

    @Test
    void aSystemTheCallerMayNotSeeIsNotARefusalBecauseItAnswersLikeAMissingOne() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-a508", "probing.person@ci.annualva.test", "Probing Person"));

        assertThat(api.get("/api/probe/systems/" + arrange.system() + "/VIEW").status()).isEqualTo(404);

        assertThat(entries(reader(), "action=REQUEST_REFUSED&actor=" + userId(api)))
                .as("ADR 0013: not yours answers 404, the same as missing, and is not told apart in the log either")
                .isEmpty();
    }

    @Test
    void aStepUpIsAnEntryAtEachStepAndAFailedOneNamesWhy() {
        var identity = TestIdentity.of("af-a509", "stepup.trail@ci.annualva.test", "Step Up Trail")
                .withAuthTime(clock.instant());
        SignIn.signedIn(api, issuer, identity);
        api.withCsrf();
        clock.advance(Duration.ofMinutes(10));

        SignIn.stepUp(api, issuer, START, identity.withAuthTime(clock.instant().minus(Duration.ofMinutes(10))));
        SignIn.stepUp(api, issuer, START, identity.withAuthTime(clock.instant()));

        List<JsonNode> mine = entries(reader(), "actor=" + userId(api));
        assertThat(actions(mine)).containsExactlyInAnyOrder(
                "SIGN_IN", "STEP_UP_STARTED", "STEP_UP_FAILED", "STEP_UP_STARTED", "STEP_UP_OK");
        JsonNode failed = only(mine, "STEP_UP_FAILED");
        assertThat(failed.get("outcome").asString()).isEqualTo("REFUSED");
        assertThat(failed.get("detail").get("reason").asString()).isEqualTo("stale-auth-time");
        assertThat(failed.get("detail").get("intent").asString()).isEqualTo("probe-sensitive");
        JsonNode ok = only(mine, "STEP_UP_OK");
        assertThat(ok.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(ok.get("detail").get("intent").asString()).isEqualTo("probe-sensitive");
    }

    @Test
    void anEntryIsWrittenInTheCommandsTransactionAndCommitsWithIt() {
        ApiClient poc = signedInPoc();
        UUID system = UUID.randomUUID();
        UUID submission = UUID.randomUUID();
        UUID finding = UUID.randomUUID();

        assertThat(audited(poc, system, submission, finding, "commit").status()).isEqualTo(200);

        List<JsonNode> written = entries(poc, "system=" + system);
        assertThat(written).hasSize(1);
        JsonNode entry = written.get(0);
        assertThat(entry.get("actorId").asString()).isEqualTo(userId(poc));
        assertThat(entry.get("systemId").asString()).isEqualTo(system.toString());
        assertThat(entry.get("submissionId").asString()).isEqualTo(submission.toString());
        assertThat(entry.get("findingId").asString()).as("a Finding by id only").isEqualTo(finding.toString());
        assertThat(entry.get("detail").get("count").asInt()).isEqualTo(3);
    }

    @Test
    void anEntryIsRolledBackWithTheCommandThatFailedButTheRefusalIsKept() {
        ApiClient poc = signedInPoc();
        UUID system = UUID.randomUUID();

        assertThat(audited(poc, system, UUID.randomUUID(), UUID.randomUUID(), "rollback").status()).isEqualTo(403);

        assertThat(entries(poc, "system=" + system))
                .as("the log never says something happened that did not")
                .isEmpty();
        List<JsonNode> refused = entries(poc, "action=REQUEST_REFUSED&actor=" + userId(poc));
        assertThat(refused).as("the refusal outlives the rolled-back command").hasSize(1);
        assertThat(refused.get(0).get("detail").get("reason").asString()).isEqualTo("forbidden");
    }

    @Test
    void anEntryWrittenIndependentlySurvivesTheRollbackOfTheCallersTransaction() {
        ApiClient poc = signedInPoc();
        UUID system = UUID.randomUUID();

        assertThat(audited(poc, system, UUID.randomUUID(), UUID.randomUUID(), "independent-rollback").status())
                .isEqualTo(403);

        assertThat(entries(poc, "system=" + system)).hasSize(1);
    }

    @Test
    void anEntryCannotBeWrittenWithoutATransactionToJoin() {
        ApiClient poc = signedInPoc();
        UUID system = UUID.randomUUID();

        assertThat(audited(poc, system, UUID.randomUUID(), UUID.randomUUID(), "no-transaction").status())
                .isEqualTo(500);

        assertThat(entries(poc, "system=" + system)).isEmpty();
    }

    @Test
    void aDetailValueThatIsFreeTextIsRefusedAndNothingIsWritten() {
        ApiClient poc = signedInPoc();

        var response = poc.withCsrf().postJson("/api/probe/audit-free-text?text=a%20comment%20with%20spaces", "");

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("comment");
        assertThat(entries(poc, "action=ROLE_GRANTED")).isEmpty();
    }

    private ApiClient signedInPoc() {
        ApiClient poc = anotherBrowser();
        SignIn.signedIn(poc, issuer, TestIdentity.of("af-5002", POC_TWO_EMAIL, "POC Two"));
        return poc.withCsrf();
    }

    private static ApiClient.Response audited(ApiClient poc, UUID system, UUID submission, UUID finding,
            String ending) {
        return poc.postJson("/api/probe/audited?system=" + system + "&submission=" + submission + "&finding="
                + finding + "&ending=" + ending, "");
    }

    private static JsonNode only(List<JsonNode> entries, String action) {
        List<JsonNode> matching = entries.stream().filter(entry -> entry.get("action").asString().equals(action)).toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }
}
