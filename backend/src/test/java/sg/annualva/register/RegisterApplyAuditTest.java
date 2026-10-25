package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import tools.jackson.databind.JsonNode;

/**
 * IM8 lm-4 and lm-19 for the apply: one entry with the counts, one per retired System and one per role granted or
 * revoked, written with the apply in one transaction. No entry holds an email, a System name or any text from the
 * file, and a refused or rolled-back apply writes none of them.
 */
@ExtendWith(OutputCaptureExtension.class)
class RegisterApplyAuditTest extends RegisterSeam1 {

    private static final String SENTINEL = "CVE-9999-SENTINEL";
    private static final String CM = "audit.apply.cm@ci.annualva.test";
    private static final Set<String> KEYS = Set.of("preview_id", "added", "updated", "retired", "unchanged",
            "roles_granted", "roles_revoked", "role", "source", "grant_id", "intent", "status", "reason");

    private JsonNode entries(ApiClient reader, String action, String filter) {
        var response = reader.get("/api/audit-entries?size=200&action=" + action + filter + untilToday());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }

    private static long total(ApiClient reader, String action) {
        var response = reader.get("/api/audit-entries?size=1&action=" + action);
        return ApiClient.json(response).get("total").asLong();
    }

    private static String idOf(ApiClient poc, String systemId) {
        for (JsonNode system : ApiClient.json(poc.get("/api/systems"))) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system.get("id").asString();
            }
        }
        throw new AssertionError("no System " + systemId);
    }

    @Test
    void anApplyIsOneEntryWithTheCountsAndThePreviewIdAndNothingFromTheFile() {
        registered(
                listed("SYS-AUD-KEEP", "Keep", "audit.keep@ci.annualva.test", CM, "Q1"),
                listed("SYS-AUD-GONE", "Gone", "audit.gone@ci.annualva.test", CM, "Q2"));
        ApiClient poc = vaPoc();
        String actor = userId(poc);
        JsonNode preview = preview(poc, RegisterXlsx.register()
                .row("SYS-AUD-KEEP", "Keep " + SENTINEL, "audit.keep@ci.annualva.test", CM, "Q1")
                .row("SYS-AUD-NEW", "New", "audit.new@ci.annualva.test", CM, "Q3"));

        JsonNode applied = apply(poc, preview);

        JsonNode found = null;
        for (JsonNode entry : entries(poc, "REGISTER_APPLIED", "&actor=" + actor)) {
            if (entry.get("detail").get("preview_id").asString().equals(preview.get("id").asString())) {
                found = entry;
            }
        }
        assertThat(found).as("an entry for this apply").isNotNull();
        assertThat(found.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(found.get("actorId").asString()).isEqualTo(actor);
        assertThat(found.get("actorRole").asString()).isEqualTo("VA_POC");
        assertThat(found.get("systemId").isNull()).isTrue();
        JsonNode detail = found.get("detail");
        assertThat(detail.get("added").asInt()).isEqualTo(applied.get("added").asInt()).isEqualTo(1);
        assertThat(detail.get("updated").asInt()).isEqualTo(1);
        assertThat(detail.get("retired").asInt()).isEqualTo(1);
        assertThat(detail.get("unchanged").asInt()).isZero();
        assertThat(detail.get("roles_granted").asInt()).isEqualTo(applied.get("rolesGranted").asInt());
        assertThat(detail.get("roles_revoked").asInt()).isEqualTo(applied.get("rolesRevoked").asInt());
        assertNoContent(found);
    }

    @Test
    void everyRetiredSystemIsAnEntryAboutThatSystem() {
        registered(
                listed("SYS-AUD-A", "A", "audit.a@ci.annualva.test", CM, "Q1"),
                listed("SYS-AUD-B", "B", "audit.b@ci.annualva.test", CM, "Q1"),
                listed("SYS-AUD-C", "C", "audit.c@ci.annualva.test", CM, "Q1"));
        ApiClient poc = vaPoc();
        String a = idOf(poc, "SYS-AUD-A");
        String b = idOf(poc, "SYS-AUD-B");
        JsonNode preview = preview(poc, RegisterXlsx.register().row("SYS-AUD-C", "C", "audit.c@ci.annualva.test", CM, "Q1"));

        apply(poc, preview);

        for (String system : new String[] {a, b}) {
            JsonNode retired = entries(poc, "REGISTER_SYSTEM_RETIRED", "&system=" + system);
            assertThat(retired).as("one entry for System " + system).hasSize(1);
            assertThat(retired.get(0).get("systemId").asString()).isEqualTo(system);
            assertThat(retired.get(0).get("detail").get("preview_id").asString()).isEqualTo(preview.get("id").asString());
            assertThat(retired.get(0).get("actorRole").asString()).isEqualTo("VA_POC");
            assertNoContent(retired.get(0));
        }
    }

    @Test
    void everyRoleGrantedAndRevokedIsAnEntryWithTheSystemTheRoleAndTheGrantId() {
        registered(listed("SYS-AUD-R", "R", "audit.old.smo@ci.annualva.test", CM, "Q1"));
        ApiClient poc = vaPoc();
        String system = idOf(poc, "SYS-AUD-R");
        String oldSmoGrant = arrange.grantsOf("audit.old.smo@ci.annualva.test").get(0).get("id").asString();

        JsonNode applied = apply(poc, preview(poc, RegisterXlsx.register()
                .row("SYS-AUD-R", "R", "audit.new.smo@ci.annualva.test", CM, "Q1")));

        assertThat(applied.get("rolesGranted").asInt()).isEqualTo(1);
        assertThat(applied.get("rolesRevoked").asInt()).isEqualTo(1);
        JsonNode revoked = entries(poc, "ROLE_REVOKED", "&system=" + system);
        assertThat(revoked).hasSize(1);
        assertThat(revoked.get(0).get("detail").get("role").asString()).isEqualTo("SMO");
        assertThat(revoked.get(0).get("detail").get("source").asString()).isEqualTo("REGISTER");
        assertThat(revoked.get(0).get("detail").get("grant_id").asString()).isEqualTo(oldSmoGrant);
        assertThat(revoked.get(0).get("actorRole").asString()).isEqualTo("VA_POC");
        JsonNode granted = entries(poc, "ROLE_GRANTED", "&system=" + system);
        assertThat(granted).as("the earlier arrangement granted two, this apply one").hasSize(3);
        String newGrant = arrange.grantsOf("audit.new.smo@ci.annualva.test").get(0).get("id").asString();
        assertThat(granted.get(0).get("detail").get("grant_id").asString()).as("newest first").isEqualTo(newGrant);
        assertThat(granted.get(0).get("detail").get("role").asString()).isEqualTo("SMO");
        for (JsonNode entry : granted) {
            assertNoContent(entry);
        }
        assertNoContent(revoked.get(0));
    }

    @Test
    void aRefusedApplyWritesNoApplyOrRoleEntriesAndTheStepUpRefusalIsRecorded() {
        registered(listed("SYS-AUD-X", "X", "audit.x@ci.annualva.test", CM, "Q1"));
        ApiClient poc = vaPoc();
        String actor = userId(poc);
        JsonNode preview = preview(poc, RegisterXlsx.register().row("SYS-AUD-X", "Renamed", "audit.y@ci.annualva.test", CM, "Q1"));
        long applied = total(poc, "REGISTER_APPLIED");
        long granted = total(poc, "ROLE_GRANTED");
        long revoked = total(poc, "ROLE_REVOKED");
        long retired = total(poc, "REGISTER_SYSTEM_RETIRED");
        clock.advance(Duration.ofMinutes(6));

        var stale = applyResponse(poc, preview);

        assertThat(stale.status()).isEqualTo(403);
        ApiClient later = vaPoc();
        JsonNode refusals = entries(later, "REQUEST_REFUSED", "&actor=" + actor);
        assertThat(refusals.get(0).get("detail").get("intent").asString()).isEqualTo("register-apply");
        assertThat(refusals.get(0).get("detail").get("reason").asString()).isEqualTo("step-up-required");

        JsonNode blocked = preview(later, RegisterXlsx.register().row("SYS-AUD-X", "X", "audit.x@ci.annualva.test", "", "Q1"));
        assertThat(applyResponse(later, blocked).status()).isEqualTo(409);

        assertThat(total(later, "REGISTER_APPLIED")).isEqualTo(applied);
        assertThat(total(later, "ROLE_GRANTED")).isEqualTo(granted);
        assertThat(total(later, "ROLE_REVOKED")).isEqualTo(revoked);
        assertThat(total(later, "REGISTER_SYSTEM_RETIRED")).isEqualTo(retired);
    }

    @Test
    void nothingFromTheFileReachesTheAuditLogTheApplicationLogOrAnErrorBody(CapturedOutput output) {
        ApiClient poc = vaPoc();
        String actor = userId(poc);
        JsonNode preview = preview(poc, RegisterXlsx.register()
                .row("SYS-" + SENTINEL, "Name " + SENTINEL, "smo-" + SENTINEL.toLowerCase() + "@ci.annualva.test",
                        "cm-" + SENTINEL.toLowerCase() + "@ci.annualva.test", "Q1"));

        apply(poc, preview);
        var again = applyResponse(poc, preview);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().toLowerCase()).doesNotContain("sentinel").doesNotContain("cve-9999");
        List<JsonNode> all = new ArrayList<>();
        entries(poc, "REGISTER_APPLIED", "&actor=" + actor).forEach(all::add);
        entries(poc, "ROLE_GRANTED", "&system=" + idOf(poc, "SYS-" + SENTINEL)).forEach(all::add);
        assertThat(all).hasSizeGreaterThanOrEqualTo(3);
        all.forEach(RegisterApplyAuditTest::assertNoContent);
        assertThat(output.getAll().toLowerCase()).doesNotContain("sentinel").doesNotContain("cve-9999");
    }

    private static void assertNoContent(JsonNode entry) {
        String text = entry.toString().toLowerCase();
        assertThat(text).doesNotContain("sentinel").doesNotContain("cve-9999").doesNotContain("sys-aud")
                .doesNotContain("audit.").doesNotContain("@ci.annualva.test");
        entry.get("detail").propertyNames().forEach(key -> assertThat(KEYS).contains(key));
    }
}
