package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import tools.jackson.databind.JsonNode;

/**
 * Stories 73 and 79 (IM8 ac-3): the SMO/Tech Lead and Cyber Manager roles come from the Register, scoped to each System.
 * An apply grants them, by email where the person has not signed in yet, and revokes them for anyone it no longer names
 * in the same transaction, so a former SMO/Tech Lead's very next request is refused.
 */
class RegisterRolesTest extends RegisterSeam1 {

    private static final String SMO = "roles.smo@ci.annualva.test";
    private static final String CM = "roles.cm@ci.annualva.test";

    private static List<JsonNode> live(JsonNode grants) {
        var live = new ArrayList<JsonNode>();
        grants.forEach(grant -> {
            if (!grant.get("revoked").asBoolean()) {
                live.add(grant);
            }
        });
        return live;
    }

    private static List<String> systemIds(ApiClient person) {
        var response = person.get("/api/systems");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        var ids = new ArrayList<String>();
        ApiClient.json(response).forEach(system -> ids.add(system.get("systemId").asString()));
        return ids;
    }

    private String idOf(ApiClient poc, String systemId) {
        for (JsonNode system : ApiClient.json(poc.get("/api/systems"))) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system.get("id").asString();
            }
        }
        throw new AssertionError("no System " + systemId);
    }

    @Test
    void everySystemGetsAnSmoGrantAndACyberManagerGrantScopedToItByEmailBeforeAnyoneHasSignedIn() {
        ApiClient poc = vaPoc();

        apply(poc, preview(poc, RegisterXlsx.register()
                .row("SYS-R1", "One", "Never.Signed.In.SMO@ci.annualva.test", CM, "Q1")));

        String system = idOf(poc, "SYS-R1");
        List<JsonNode> smo = live(arrange.grantsOf("never.signed.in.smo@ci.annualva.test"));
        assertThat(smo).hasSize(1);
        assertThat(smo.get(0).get("role").asString()).isEqualTo("SMO");
        assertThat(smo.get(0).get("source").asString()).isEqualTo("REGISTER");
        assertThat(smo.get(0).get("systemId").asString()).isEqualTo(system);
        assertThat(smo.get(0).get("attached").asBoolean()).as("nobody has signed in yet: granted by email").isFalse();
        List<JsonNode> manager = live(arrange.grantsOf(CM));
        assertThat(manager).hasSize(1);
        assertThat(manager.get(0).get("role").asString()).isEqualTo("CYBER_MANAGER");
        assertThat(manager.get(0).get("source").asString()).isEqualTo("REGISTER");
        assertThat(manager.get(0).get("systemId").asString()).isEqualTo(system);
    }

    @Test
    void aFirstSignInAttachesTheGrantsTheApplyMadeByEmail() {
        ApiClient poc = vaPoc();
        apply(poc, preview(poc, RegisterXlsx.register().row("SYS-R2", "Two", SMO, CM, "Q2")));

        ApiClient smo = signedInAs(SMO);
        ApiClient manager = signedInAs(CM);

        assertThat(systemIds(smo)).containsExactly("SYS-R2");
        assertThat(systemIds(manager)).containsExactly("SYS-R2");
        assertThat(live(arrange.grantsOf(SMO)).get(0).get("attached").asBoolean()).isTrue();
    }

    @Test
    void aPersonWhoHasAlreadySignedInHoldsTheirNewRoleOnTheirVeryNextRequest() {
        ApiClient smo = signedInAs(SMO);
        assertThat(smo.get("/api/systems").status()).as("no role yet").isEqualTo(403);
        ApiClient poc = vaPoc();

        apply(poc, preview(poc, RegisterXlsx.register().row("SYS-R3", "Three", SMO, CM, "Q3")));

        assertThat(systemIds(smo)).containsExactly("SYS-R3");
        assertThat(live(arrange.grantsOf(SMO)).get(0).get("attached").asBoolean()).isTrue();
    }

    @Test
    void aFormerSmoLosesAccessAtOnceAndTheNewSmoGainsIt() {
        registered(listed("SYS-R4", "Four", SMO, CM, "Q1"));
        ApiClient former = signedInAs(SMO);
        assertThat(systemIds(former)).containsExactly("SYS-R4");
        ApiClient poc = vaPoc();

        JsonNode applied = apply(poc, preview(poc, RegisterXlsx.register()
                .row("SYS-R4", "Four", "new.smo@ci.annualva.test", CM, "Q1")));

        assertThat(applied.get("rolesRevoked").asInt()).isEqualTo(1);
        assertThat(applied.get("rolesGranted").asInt()).isEqualTo(1);
        assertThat(former.get("/api/systems").status()).as("the same session's next request").isEqualTo(403);
        assertThat(live(arrange.grantsOf(SMO))).isEmpty();
        assertThat(systemIds(signedInAs("new.smo@ci.annualva.test"))).containsExactly("SYS-R4");
        assertThat(systemIds(signedInAs(CM))).as("the Cyber Manager is untouched").containsExactly("SYS-R4");
    }

    @Test
    void aFormerSmoKeepsTheirOtherSystemsAndLosesOnlyTheOneTheyNoLongerHold() {
        registered(
                listed("SYS-R5A", "Five A", SMO, CM, "Q1"),
                listed("SYS-R5B", "Five B", SMO, CM, "Q2"));
        ApiClient smo = signedInAs(SMO);
        assertThat(systemIds(smo)).containsExactlyInAnyOrder("SYS-R5A", "SYS-R5B");
        ApiClient poc = vaPoc();

        apply(poc, preview(poc, RegisterXlsx.register()
                .row("SYS-R5A", "Five A", "someone.else@ci.annualva.test", CM, "Q1")
                .row("SYS-R5B", "Five B", SMO, CM, "Q2")));

        assertThat(systemIds(smo)).containsExactly("SYS-R5B");
    }

    @Test
    void aFormerCyberManagerLosesAccessAtOnceToo() {
        registered(listed("SYS-R6", "Six", SMO, CM, "Q1"));
        ApiClient manager = signedInAs(CM);
        assertThat(systemIds(manager)).containsExactly("SYS-R6");
        ApiClient poc = vaPoc();

        apply(poc, preview(poc, RegisterXlsx.register().row("SYS-R6", "Six", SMO, "new.cm@ci.annualva.test", "Q1")));

        assertThat(manager.get("/api/systems").status()).isEqualTo(403);
        assertThat(systemIds(signedInAs("new.cm@ci.annualva.test"))).containsExactly("SYS-R6");
    }

    @Test
    void retiringASystemRevokesItsGrantsAndReinstatingItGrantsThemAgain() {
        registered(
                listed("SYS-R7", "Seven", SMO, CM, "Q1"),
                listed("SYS-R7-OTHER", "Other", "other.smo@ci.annualva.test", "other.cm@ci.annualva.test", "Q1"));
        ApiClient smo = signedInAs(SMO);
        assertThat(systemIds(smo)).containsExactly("SYS-R7");
        ApiClient poc = vaPoc();

        JsonNode retired = apply(poc, preview(poc, RegisterXlsx.register()
                .row("SYS-R7-OTHER", "Other", "other.smo@ci.annualva.test", "other.cm@ci.annualva.test", "Q1")));

        assertThat(retired.get("retired").asInt()).isEqualTo(1);
        assertThat(retired.get("rolesRevoked").asInt()).isEqualTo(2);
        assertThat(smo.get("/api/systems").status()).as("a retired System's SMO has no role left").isEqualTo(403);

        apply(poc, preview(poc, file(List.of(
                listed("SYS-R7", "Seven", SMO, CM, "Q1"),
                listed("SYS-R7-OTHER", "Other", "other.smo@ci.annualva.test", "other.cm@ci.annualva.test", "Q1")))));

        assertThat(systemIds(smo)).as("named again, the same person holds it again").containsExactly("SYS-R7");
        assertThat(arrange.grantsOf(SMO)).as("a revoked grant stays as it was and the new one is a new row").hasSize(2);
        assertThat(live(arrange.grantsOf(SMO))).hasSize(1);
    }

    @Test
    void applyingTheSameRegisterAgainGrantsAndRevokesNothing() {
        registered(listed("SYS-R8", "Eight", SMO, CM, "Q1"));
        ApiClient poc = vaPoc();

        JsonNode applied = apply(poc, preview(poc, RegisterXlsx.register()
                .row("SYS-R8", "Eight", SMO.toUpperCase(), CM, "Q1")));

        assertThat(applied.get("unchanged").asInt()).isEqualTo(1);
        assertThat(applied.get("rolesGranted").asInt()).isZero();
        assertThat(applied.get("rolesRevoked").asInt()).isZero();
        assertThat(arrange.grantsOf(SMO)).hasSize(1);
    }

    @Test
    void aPersonCanBeSmoOfOneSystemAndCyberManagerOfAnotherAndEachGrantIsScoped() {
        ApiClient poc = vaPoc();
        apply(poc, preview(poc, RegisterXlsx.register()
                .row("SYS-R9A", "Nine A", SMO, CM, "Q1")
                .row("SYS-R9B", "Nine B", "nine.smo@ci.annualva.test", SMO, "Q1")));

        ApiClient both = signedInAs(SMO);

        assertThat(systemIds(both)).containsExactlyInAnyOrder("SYS-R9A", "SYS-R9B");
        List<JsonNode> grants = live(arrange.grantsOf(SMO));
        assertThat(grants).extracting(grant -> grant.get("role").asString()).containsExactlyInAnyOrder("SMO", "CYBER_MANAGER");
        assertThat(grants).extracting(grant -> grant.get("systemId").asString()).doesNotHaveDuplicates();
    }

    @Test
    void aVaPocNamedInTheRegisterKeepsTheirOrganisationWideRoleAndGainsTheSystemRole() {
        ApiClient poc = vaPoc();

        apply(poc, preview(poc, RegisterXlsx.register().row("SYS-R10", "Ten", POC_ONE, CM, "Q1")));

        List<JsonNode> grants = live(arrange.grantsOf(POC_ONE));
        assertThat(grants).extracting(grant -> grant.get("role").asString()).containsExactlyInAnyOrder("VA_POC", "SMO");
        assertThat(grants).filteredOn(grant -> grant.get("role").asString().equals("VA_POC"))
                .extracting(grant -> grant.get("source").asString()).containsExactly("CONFIG");
        apply(poc, preview(poc, RegisterXlsx.register().row("SYS-R10", "Ten", "another@ci.annualva.test", CM, "Q1")));
        assertThat(live(arrange.grantsOf(POC_ONE))).extracting(grant -> grant.get("role").asString())
                .as("an apply never touches a configured VA POC").containsExactly("VA_POC");
    }

    @Test
    void aRefusedApplyLeavesTheGrantsAsTheyWere() {
        registered(listed("SYS-R11", "Eleven", SMO, CM, "Q1"));
        ApiClient first = vaPoc();
        ApiClient second = otherVaPoc();
        JsonNode stale = preview(second, RegisterXlsx.register().row("SYS-R11", "Eleven", "x@ci.annualva.test", CM, "Q1"));
        apply(first, preview(first, RegisterXlsx.register().row("SYS-R11", "Eleven", SMO, CM, "Q2")));

        assertThat(applyResponse(second, stale).status()).isEqualTo(409);

        assertThat(live(arrange.grantsOf(SMO))).hasSize(1);
        assertThat(arrange.grantsOf("x@ci.annualva.test")).isEmpty();
    }
}
