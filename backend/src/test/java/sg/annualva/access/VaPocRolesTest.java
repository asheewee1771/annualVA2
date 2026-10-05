package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/** Story 75: the two VA POCs come from configuration, as VA_POC grants with source CONFIG. */
class VaPocRolesTest extends Seam1Test {

    private static final String POC_ONE = "poc.one@ci.annualva.test";
    private static final String POC_TWO = "poc.two@ci.annualva.test";

    @Autowired
    private VaPocSync startupSync;

    private static String roles(ApiClient api) {
        var names = new java.util.ArrayList<String>();
        ApiClient.json(api.get("/api/session")).get("roles").forEach(role -> names.add(role.asString()));
        return String.join(",", names);
    }

    @Test
    void bothConfiguredPeopleHoldTheVaPocRoleFromTheirFirstSignIn() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-5001", POC_ONE, "POC One"));
        assertThat(roles(api)).isEqualTo("VA_POC");
        assertThat(api.get("/api/probe/va-poc").status()).isEqualTo(200);

        var second = anotherBrowser();
        SignIn.signedIn(second, issuer, TestIdentity.of("af-5002", POC_TWO, "POC Two"));
        assertThat(roles(second)).isEqualTo("VA_POC");
        assertThat(second.get("/api/probe/va-poc").status()).isEqualTo(200);
    }

    @Test
    void startupWroteExactlyOneConfigGrantForEachAndNoOtherVaPoc() {
        for (String email : new String[] {POC_ONE, POC_TWO}) {
            var grants = arrange.grantsOf(email);
            assertThat(grants.size()).isEqualTo(1);
            assertThat(grants.get(0).get("role").asString()).isEqualTo("VA_POC");
            assertThat(grants.get(0).get("source").asString()).isEqualTo("CONFIG");
        }
    }

    @Test
    void syncingAgainChangesNothing() {
        startupSync.sync();
        startupSync.sync();

        assertThat(arrange.grantsOf(POC_ONE).size()).isEqualTo(1);
        assertThat(arrange.grantsOf(POC_TWO).size()).isEqualTo(1);
    }

    @Test
    void aConfigGrantForSomeoneNoLongerConfiguredIsRevokedAtTheNextStartup() {
        String retired = "retired.poc@ci.annualva.test";
        arrange.grant("VA_POC", retired, null, "CONFIG", null);
        SignIn.signedIn(api, issuer, TestIdentity.of("af-5003", retired, "Retired POC"));
        assertThat(roles(api)).isEqualTo("VA_POC");

        startupSync.sync();

        assertThat(roles(api)).as("the former VA POC loses the role on the next call").isEmpty();
        assertThat(api.get("/api/probe/va-poc").status()).isEqualTo(403);
        var current = anotherBrowser();
        SignIn.signedIn(current, issuer, TestIdentity.of("af-5001", POC_ONE, "POC One"));
        assertThat(roles(current)).as("the configured VA POCs are untouched").isEqualTo("VA_POC");
    }

    @Test
    void onlyConfigurationCanMakeAVaPoc() {
        String other = "other.poc@ci.annualva.test";
        var refused = arrange.tryGrant("VA_POC", other, null, "REGISTER");
        assertThat(refused).as("the role store itself refuses a VA POC from any other source").isNotEqualTo(200);
        assertThat(arrange.grantsOf(other).size()).isZero();
    }
}
