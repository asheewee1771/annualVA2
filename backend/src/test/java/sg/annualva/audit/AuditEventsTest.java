package sg.annualva.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * IM8 lm-15: every audit entry is also an ECS event, on a logger of its own ({@code annualva.audit}) so it can be
 * routed apart from the application's log, announced only once the entry has been committed.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditEventsTest extends AuditSeam1 {

    private static final String AUDIT_LOGGER = "\"logger\":\"annualva.audit\"";

    @Test
    void aSignInIsEmittedOnTheAuditLoggerWithTheUserAndRolesAndNeverTheEmail(CapturedOutput output) {
        arrange.grant("CYBER_MANAGER", "events.person@ci.annualva.test", arrange.system());
        SignIn.signedIn(api, issuer, TestIdentity.of("af-a801", "events.person@ci.annualva.test", "Events Person"));
        String userId = userId(api);

        List<String> events = auditEvents(output, userId);

        assertThat(events).anySatisfy(line -> assertThat(line)
                .contains("\"action\":\"sign-in\"")
                .contains("\"outcome\":\"success\"")
                .contains("\"category\":\"authentication\"")
                .contains("CYBER_MANAGER")
                .contains("\"trace\":{\"id\":")
                .contains("\"id\":\"" + userId + "\""));
        assertThat(output.getAll()).doesNotContain("events.person@ci.annualva.test").doesNotContain("Events Person");
    }

    @Test
    void aRefusalAndAFailedSignInAreEmittedWithTheirFixedReasons(CapturedOutput output) {
        assertThat(api.get("/api/anything").status()).isEqualTo(401);
        SignIn.through(anotherBrowser(), issuer, TestIdentity.of("af-a802", "events.refused@ci.annualva.test", "Events Refused")
                .withAmr(List.of("pwd")));

        List<String> events = output.getAll().lines().filter(line -> line.contains(AUDIT_LOGGER)).toList();

        assertThat(events).anySatisfy(line -> assertThat(line)
                .contains("\"action\":\"request-refused\"")
                .contains("\"reason\":\"unauthenticated\"")
                .contains("\"outcome\":\"failure\""));
        assertThat(events).anySatisfy(line -> assertThat(line)
                .contains("\"action\":\"sign-in-refused\"")
                .contains("\"reason\":\"mfa-not-evidenced\""));
    }

    @Test
    void anEntryAboutASystemAndAFindingIsEmittedByIdOnlyOnceItHasCommitted(CapturedOutput output) {
        ApiClient poc = reader().withCsrf();
        UUID committed = UUID.randomUUID();
        UUID rolledBack = UUID.randomUUID();
        UUID survived = UUID.randomUUID();
        UUID finding = UUID.randomUUID();

        assertThat(poc.postJson(probe(committed, finding, "commit"), "").status()).isEqualTo(200);
        assertThat(poc.postJson(probe(rolledBack, UUID.randomUUID(), "rollback"), "").status()).isEqualTo(403);
        assertThat(poc.postJson(probe(survived, UUID.randomUUID(), "independent-rollback"), "").status()).isEqualTo(403);

        List<String> events = output.getAll().lines().filter(line -> line.contains(AUDIT_LOGGER)).toList();
        assertThat(events).anySatisfy(line -> assertThat(line)
                .contains(committed.toString()).contains(finding.toString()).contains("\"action\":\"role-granted\""));
        assertThat(events).anySatisfy(line -> assertThat(line).contains(survived.toString()));
        assertThat(output.getAll()).as("a rolled-back entry is never announced").doesNotContain(rolledBack.toString());
    }

    private static String probe(UUID system, UUID finding, String ending) {
        return "/api/probe/audited?system=" + system + "&submission=" + UUID.randomUUID() + "&finding=" + finding
                + "&ending=" + ending;
    }

    private static List<String> auditEvents(CapturedOutput output, String userId) {
        return output.getAll().lines().filter(line -> line.contains(AUDIT_LOGGER) && line.contains(userId)).toList();
    }
}
