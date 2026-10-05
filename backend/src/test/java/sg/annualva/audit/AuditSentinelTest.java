package sg.annualva.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * IM8 lm-19 and the house rule that report content (CVE ids, rule names, hosts, free text) never appears in logs, audit
 * entries or error bodies. The sentinel stands for all of it: it is put into every place a request, a token or a
 * person's own text can carry something, the requests are refused and audited, and then neither the audit log nor
 * the application's output may show it, nor the person's email or name.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditSentinelTest extends AuditSeam1 {

    private static final String SENTINEL = "CVE-9999-SENTINEL";
    private static final String EMAIL = "cve-9999-sentinel@ci.annualva.test";
    private static final Set<String> ALLOWED_KEYS = Set.of("state_from", "state_to", "batch_id", "count", "file_id",
            "reject_code", "reason", "intent", "status", "error_code", "role", "source", "grant_id");

    @Test
    void noEntryAndNoLogLineCarriesWhatARequestOrATokenHeld(CapturedOutput output) {
        var identity = TestIdentity.of("af-a901", EMAIL, "Name " + SENTINEL)
                .withClaim("note", SENTINEL)
                .withAuthTime(clock.instant());
        String system = arrange.system();
        arrange.grant("CYBER_MANAGER", EMAIL, system);
        SignIn.signedIn(api, issuer, identity);
        // No CSRF token fetched yet: refused as csrf-rejected.
        api.postJson("/api/probe/sensitive", "{\"comment\":\"" + SENTINEL + "\"}");
        api.withCsrf();
        Map<String, String> hostile = Map.of(
                "User-Agent", SENTINEL,
                "Referer", "https://host-" + SENTINEL.toLowerCase() + ".example/rule/" + SENTINEL,
                "X-Forwarded-For", SENTINEL,
                "X-Anything", SENTINEL);

        // Refused as a signed-in person: forbidden, no CSRF token, no fresh sign-in.
        api.get("/api/probe/va-poc?q=" + SENTINEL + "&host=" + SENTINEL, hostile);
        api.get("/api/probe/va-poc/" + SENTINEL, hostile);
        clock.advance(Duration.ofMinutes(6));
        api.postJson("/api/probe/sensitive", "{\"justification\":\"" + SENTINEL + "\",\"rule\":\"" + SENTINEL + "\"}");
        api.postJson("/api/probe/audit-free-text?text=" + SENTINEL.replace("-", "%20"), "");
        SignIn.stepUp(api, issuer, "/auth/step-up?intent=probe-sensitive&returnTo=/" + SENTINEL.toLowerCase() + "/x",
                identity.withAuthTime(clock.instant().minus(Duration.ofHours(1))));
        // Refused with no session at all.
        anotherBrowser().get("/api/" + SENTINEL + "/rule", hostile);
        anotherBrowser().get("/" + SENTINEL, hostile);
        anotherBrowser().postJson("/api/" + SENTINEL, "{\"comment\":\"" + SENTINEL + "\"}");
        // A sign-in the application refuses, with the sentinel in its email, name and a claim.
        SignIn.through(anotherBrowser(), issuer, TestIdentity.of("af-a902", EMAIL, "Name " + SENTINEL)
                .withClaim("note", SENTINEL).withAmr(List.of("pwd")));

        List<JsonNode> entries = entries(reader(), "");

        assertThat(entries).as("the requests above were audited").hasSizeGreaterThanOrEqualTo(8);
        for (JsonNode entry : entries) {
            String text = entry.toString().toLowerCase();
            assertThat(text).doesNotContain("sentinel").doesNotContain("cve-9999").doesNotContain("host-")
                    .doesNotContain("ci.annualva.test");
            entry.get("detail").propertyNames().forEach(key -> assertThat(ALLOWED_KEYS).contains(key));
        }
        assertThat(output.getAll().toLowerCase()).doesNotContain("sentinel").doesNotContain(EMAIL);
    }

}
