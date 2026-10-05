package sg.annualva.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * IM8 ac-1: the calendar names no System, so it is open to every role and to nobody without one; a request needs a
 * session, and a person who holds no role is refused with the same problem body as anywhere else.
 */
class CycleAccessTest extends CycleSeam1 {

    private static final List<String> PATHS = List.of("/api/cycles", "/api/cycles/2027");

    @Test
    void noSessionIsUnauthenticated() {
        for (String path : PATHS) {
            var response = api.get(path);
            assertThat(response.status()).as(path).isEqualTo(401);
            assertThat(response.header("content-type")).startsWith("application/problem+json");
        }
    }

    @Test
    void aSignedInPersonWithNoRoleIsForbidden() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-9101", "calendar.norole@ci.annualva.test", "Calendar No Role"));

        for (String path : PATHS) {
            var response = api.get(path);
            assertThat(response.status()).as(path).isEqualTo(403);
            assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/forbidden");
        }
    }

    @Test
    void everyRoleCanReadTheCalendarAndGetsTheSameAnswer() {
        String system = arrange.system();
        arrange.grant("SMO", "calendar.smo@ci.annualva.test", system);
        arrange.grant("CYBER_MANAGER", "calendar.manager@ci.annualva.test", system);
        arrange.grant("AUDITOR", "calendar.auditor@ci.annualva.test", null, "GRANT", null);
        var people = new java.util.LinkedHashMap<String, String>();
        people.put("poc.one@ci.annualva.test", "af-5001");
        people.put("calendar.smo@ci.annualva.test", "af-9102");
        people.put("calendar.manager@ci.annualva.test", "af-9103");
        people.put("calendar.auditor@ci.annualva.test", "af-9104");
        String expected = null;
        for (var person : people.entrySet()) {
            ApiClient browser = anotherBrowser();
            SignIn.signedIn(browser, issuer, TestIdentity.of(person.getValue(), person.getKey(), "Calendar Reader"));
            for (String path : PATHS) {
                assertThat(browser.get(path).status()).as(person.getKey() + " " + path).isEqualTo(200);
            }
            String body = browser.get("/api/cycles").body();
            expected = expected == null ? body : expected;
            assertThat(body).isEqualTo(expected);
        }
    }

    @Test
    void thereIsNoWayToChangeTheCalendarThroughTheApi() {
        ApiClient poc = signedInOn("2027-08-02").withCsrf();

        for (String path : PATHS) {
            assertThat(poc.postJson(path, "{}").status()).as(path).isIn(404, 405);
        }
    }
}
