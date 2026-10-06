package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Stories 2, 12 and 78: {@code GET /api/systems} lists the Register. A VA POC sees every System; an SMO/Tech Lead or
 * Cyber Manager sees only the Systems their roles name; each row carries the SMO/Tech Lead, Cyber Manager, Quarter,
 * the retired flag and, for a VA POC, the Unlinked SMO flag, which clears once the person signs in.
 */
class RegisterSystemsTest extends RegisterSeam1 {

    private static final String SMO_ONE = "list.smo.one@ci.annualva.test";
    private static final String SMO_TWO = "list.smo.two@ci.annualva.test";
    private static final String CM_ONE = "list.cm.one@ci.annualva.test";
    private static final String CM_TWO = "list.cm.two@ci.annualva.test";

    private void arrangeTwoSystems() {
        registered(
                List.of(listed("SYS-L1", "List one", SMO_ONE, CM_ONE, "Q1"),
                        listed("SYS-L2", "List two", SMO_TWO, CM_TWO, "Q3")),
                List.of(listed("SYS-L3", "List three", "list.smo.three@ci.annualva.test", CM_ONE, "Q2")));
    }

    private static JsonNode list(ApiClient client) {
        var response = client.get("/api/systems");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    private static JsonNode row(JsonNode systems, String systemId) {
        for (JsonNode system : systems) {
            if (system.get("systemId").asString().equals(systemId)) {
                return system;
            }
        }
        throw new AssertionError("no System " + systemId + " in " + systems);
    }

    private static List<String> ids(JsonNode systems) {
        var ids = new ArrayList<String>();
        systems.forEach(system -> ids.add(system.get("systemId").asString()));
        return ids;
    }

    @Test
    void aVaPocSeesEverySystemWithItsPeopleQuarterRetiredFlagAndUnlinkedSmo() {
        arrangeTwoSystems();
        signedInAs(SMO_ONE);

        JsonNode systems = list(vaPoc());

        assertThat(ids(systems)).containsExactly("SYS-L1", "SYS-L2", "SYS-L3");
        JsonNode one = row(systems, "SYS-L1");
        assertThat(UUID.fromString(one.get("id").asString())).isNotNull();
        assertThat(one.get("name").asString()).isEqualTo("List one");
        assertThat(one.get("smoEmail").asString()).isEqualTo(SMO_ONE);
        assertThat(one.get("cyberManagerEmail").asString()).isEqualTo(CM_ONE);
        assertThat(one.get("quarter").asString()).isEqualTo("Q1");
        assertThat(one.get("retired").asBoolean()).isFalse();
        assertThat(one.get("unlinkedSmo").asBoolean()).as("this SMO has signed in").isFalse();
        assertThat(row(systems, "SYS-L2").get("unlinkedSmo").asBoolean()).as("this one has not").isTrue();
        assertThat(row(systems, "SYS-L2").get("quarter").asString()).isEqualTo("Q3");
        JsonNode retired = row(systems, "SYS-L3");
        assertThat(retired.get("retired").asBoolean()).isTrue();
        assertThat(retired.get("unlinkedSmo").asBoolean()).as("a retired System has nothing to link").isFalse();
    }

    @Test
    void theUnlinkedSmoFlagClearsAsSoonAsThePersonSignsIn() {
        arrangeTwoSystems();
        ApiClient poc = vaPoc();
        assertThat(row(list(poc), "SYS-L2").get("unlinkedSmo").asBoolean()).isTrue();

        signedInAs(SMO_TWO);

        assertThat(row(list(poc), "SYS-L2").get("unlinkedSmo").asBoolean()).isFalse();
    }

    @Test
    void anSmoSeesOnlyTheirOwnSystemsAndNoUnlinkedFlag() {
        arrangeTwoSystems();

        JsonNode systems = list(signedInAs(SMO_ONE));

        assertThat(ids(systems)).containsExactly("SYS-L1");
        assertThat(row(systems, "SYS-L1").get("cyberManagerEmail").asString()).isEqualTo(CM_ONE);
        assertThat(row(systems, "SYS-L1").get("unlinkedSmo").isNull()).as("the flag is the VA POCs' to fix").isTrue();
    }

    @Test
    void aCyberManagerSeesOnlyTheSystemsAssignedToThem() {
        arrangeTwoSystems();

        assertThat(ids(list(signedInAs(CM_ONE)))).as("SYS-L3 is retired and its grants are gone").containsExactly("SYS-L1");
        assertThat(ids(list(signedInAs(CM_TWO)))).containsExactly("SYS-L2");
    }

    @Test
    void aPersonWithNoRoleAnAuditorAndAStrangerAreRefusedAndAnonymousIsUnauthenticated() {
        arrangeTwoSystems();
        ApiClient noRole = signedInAs("list.nobody@ci.annualva.test");
        ApiClient auditor = signedInAs("list.auditor@ci.annualva.test");
        arrange.grant("AUDITOR", "list.auditor@ci.annualva.test", null, "GRANT", "2027-12-31T00:00:00Z");

        assertThat(noRole.get("/api/systems").status()).isEqualTo(403);
        assertThat(auditor.get("/api/systems").status()).as("an Auditor has no System scope yet").isEqualTo(403);
        var anonymous = anotherBrowser().get("/api/systems");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(problemType(anonymous)).isEqualTo("https://annualva/problems/unauthenticated");
    }

    @Test
    void theRegisterIsAnEmptyListBeforeAnyApply() {
        assertThat(list(vaPoc())).isEmpty();
    }

    @Test
    void theListCannotBeChangedThroughItsAddress() {
        ApiClient poc = vaPoc();

        assertThat(poc.postJson("/api/systems", "{}").status()).isIn(403, 405);
    }
}
