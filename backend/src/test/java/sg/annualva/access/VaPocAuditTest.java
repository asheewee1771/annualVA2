package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.AuditEntries;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/** Story 84 (role change): the VA POC grants and revocations that configuration makes are audit entries, by grant id and never by email. */
class VaPocAuditTest extends Seam1Test {

    @Autowired
    private VaPocSync sync;

    @Test
    void startupGrantedTheVaPocRoleToEachConfiguredPersonAndSaidSo() {
        var reader = reader();

        List<JsonNode> granted = allEntries(reader, "action=ROLE_GRANTED");

        for (String email : List.of("poc.one@ci.annualva.test", "poc.two@ci.annualva.test")) {
            String grantId = arrange.grantsOf(email).get(0).get("id").asString();
            assertThat(granted).as(email).anySatisfy(entry -> {
                assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
                assertThat(entry.get("actorId").isNull()).as("the deployment is the actor").isTrue();
                assertThat(entry.path("detail").path("grant_id").asString("")).isEqualTo(grantId);
                assertThat(entry.get("detail").get("role").asString()).isEqualTo("VA_POC");
                assertThat(entry.get("detail").get("source").asString()).isEqualTo("CONFIG");
            });
        }
        assertThat(granted.toString()).doesNotContain("@ci.annualva.test");
    }

    @Test
    void aVaPocNoLongerConfiguredIsRevokedAndSyncingAgainWritesNothingNew() {
        String retired = "audit.retired.poc@ci.annualva.test";
        String grantId = arrange.grant("VA_POC", retired, null, "CONFIG", null);
        var reader = reader();
        long before = allEntries(reader, "").size();

        sync.sync();

        List<JsonNode> revoked = allEntries(reader, "action=ROLE_REVOKED");
        assertThat(revoked).filteredOn(entry -> entry.path("detail").path("grant_id").asString("").equals(grantId))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
                    assertThat(entry.get("detail").get("role").asString()).isEqualTo("VA_POC");
                    assertThat(entry.get("detail").get("source").asString()).isEqualTo("CONFIG");
                });
        assertThat(revoked.toString()).doesNotContain(retired);
        long afterFirst = allEntries(reader, "").size();
        assertThat(afterFirst).isGreaterThan(before);

        sync.sync();
        sync.sync();

        assertThat(allEntries(reader, "").size()).as("a sync that changes nothing writes nothing").isEqualTo(afterFirst);
    }

    private ApiClient reader() {
        ApiClient reader = anotherBrowser();
        SignIn.signedIn(reader, issuer, TestIdentity.of("af-5002", "poc.two@ci.annualva.test", "POC Two"));
        return reader;
    }

    /** Every entry the query answers; reads of the log, which counting it writes, are left out ({@link AuditEntries}). */
    private static List<JsonNode> allEntries(ApiClient reader, String query) {
        return AuditEntries.all(reader, query);
    }
}
