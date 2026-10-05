package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * Story 78 and IM8 ac-1/as-7: a person sees only the Systems their roles allow, and a System they
 * may not see answers exactly like one that does not exist.
 */
class SystemScopeTest extends Seam1Test {

    private ApiClient signedInAs(String ssoId, String email) {
        var browser = anotherBrowser();
        SignIn.signedIn(browser, issuer, TestIdentity.of(ssoId, email, email));
        return browser;
    }

    private static List<String> visible(ApiClient browser, String need) {
        var ids = new ArrayList<String>();
        ApiClient.json(browser.get("/api/probe/systems?need=" + need)).forEach(id -> ids.add(id.asString()));
        return ids;
    }

    /** The answer without the correlation id and the caller's own request path, so two answers can be compared. */
    private static String answer(ApiClient.Response response) {
        var body = (ObjectNode) ApiClient.json(response);
        body.remove("traceId");
        body.remove("instance");
        return response.status() + " " + response.header("content-type") + " " + body;
    }

    @Test
    void anSmoReachesTheirOwnSystemsAndNoOthers() {
        String own = arrange.system();
        String other = arrange.system();
        arrange.grant("SMO", "scope.smo@ci.annualva.test", own);
        var smo = signedInAs("af-6001", "scope.smo@ci.annualva.test");

        assertThat(smo.get("/api/probe/systems/" + own + "/VIEW").status()).isEqualTo(200);
        assertThat(smo.get("/api/probe/systems/" + own + "/SUBMIT").status()).isEqualTo(200);
        assertThat(smo.get("/api/probe/systems/" + other + "/VIEW").status()).isEqualTo(404);
        assertThat(visible(smo, "VIEW")).containsExactly(own);
    }

    @Test
    void aSystemTheCallerMayNotSeeAnswersExactlyLikeOneThatDoesNotExist() {
        String exists = arrange.system();
        arrange.grant("SMO", "scope.stranger@ci.annualva.test", arrange.system());
        var stranger = signedInAs("af-6002", "scope.stranger@ci.annualva.test");

        var notMine = stranger.get("/api/probe/systems/" + exists + "/VIEW");
        var missing = stranger.get("/api/probe/systems/" + UUID.randomUUID() + "/VIEW");

        assertThat(notMine.status()).isEqualTo(404);
        assertThat(answer(notMine)).isEqualTo(answer(missing));
        assertThat(ApiClient.json(notMine).get("type").asString()).isEqualTo("https://annualva/problems/not-found");
    }

    @Test
    void aVaPocOverASystemThatDoesNotExistGetsTheSameAnswerToo() {
        var poc = signedInAs("af-5001", "poc.one@ci.annualva.test");
        var noSuchSystem = poc.get("/api/probe/systems/" + UUID.randomUUID() + "/VIEW");

        arrange.grant("SMO", "scope.other@ci.annualva.test", arrange.system());
        var other = signedInAs("af-6003", "scope.other@ci.annualva.test");
        var notMine = other.get("/api/probe/systems/" + arrange.system() + "/VIEW");

        assertThat(noSuchSystem.status()).isEqualTo(404);
        assertThat(answer(noSuchSystem)).isEqualTo(answer(notMine));
    }

    @Test
    void aCyberManagerReviewsOnlyTheSystemsAssignedToThem() {
        String assigned = arrange.system();
        String elsewhere = arrange.system();
        arrange.grant("CYBER_MANAGER", "scope.cm@ci.annualva.test", assigned);
        var cm = signedInAs("af-6004", "scope.cm@ci.annualva.test");

        assertThat(cm.get("/api/probe/systems/" + assigned + "/REVIEW").status()).isEqualTo(200);
        assertThat(cm.get("/api/probe/systems/" + assigned + "/VIEW").status()).isEqualTo(200);
        assertThat(cm.get("/api/probe/systems/" + elsewhere + "/REVIEW").status()).isEqualTo(404);
        assertThat(cm.get("/api/probe/systems/" + assigned + "/SUBMIT").status())
                .as("reviewing a System does not let you submit for it").isEqualTo(404);
        assertThat(visible(cm, "REVIEW")).containsExactly(assigned);
        assertThat(visible(cm, "SUBMIT")).isEmpty();
    }

    @Test
    void aPersonWithTwoRolesSeesTheUnionForViewingButEachRoleOnlyForItsOwnStep() {
        String asSmo = arrange.system();
        String asCm = arrange.system();
        arrange.grant("SMO", "scope.two@ci.annualva.test", asSmo);
        arrange.grant("CYBER_MANAGER", "scope.two@ci.annualva.test", asCm);
        var both = signedInAs("af-6005", "scope.two@ci.annualva.test");

        assertThat(visible(both, "VIEW")).containsExactlyInAnyOrder(asSmo, asCm);
        assertThat(visible(both, "SUBMIT")).containsExactly(asSmo);
        assertThat(visible(both, "REVIEW")).containsExactly(asCm);
    }

    @Test
    void aVaPocSeesAndEndorsesEverySystemButCannotSubmitOrReview() {
        String one = arrange.system();
        String two = arrange.system();
        var poc = signedInAs("af-5001", "poc.one@ci.annualva.test");

        assertThat(visible(poc, "VIEW")).contains(one, two);
        assertThat(visible(poc, "ENDORSE")).contains(one, two);
        assertThat(poc.get("/api/probe/systems/" + one + "/ENDORSE").status()).isEqualTo(200);
        assertThat(visible(poc, "SUBMIT")).isEmpty();
        assertThat(visible(poc, "REVIEW")).isEmpty();
    }

    @Test
    void nobodyButAVaPocEndorses() {
        String system = arrange.system();
        arrange.grant("SMO", "scope.noendorse@ci.annualva.test", system);
        arrange.grant("CYBER_MANAGER", "scope.noendorse@ci.annualva.test", system);
        var person = signedInAs("af-6006", "scope.noendorse@ci.annualva.test");

        assertThat(person.get("/api/probe/systems/" + system + "/ENDORSE").status()).isEqualTo(404);
        assertThat(visible(person, "ENDORSE")).isEmpty();
    }

    @Test
    void anAuditorHasNoSystemScopeUntilTheirScopeIsGranted() {
        String system = arrange.system();
        arrange.grant("AUDITOR", "scope.auditor@ci.annualva.test", null, "GRANT", null);
        var auditor = signedInAs("af-6007", "scope.auditor@ci.annualva.test");

        assertThat(auditor.get("/api/probe/systems/" + system + "/VIEW").status()).isEqualTo(404);
        assertThat(visible(auditor, "VIEW")).isEmpty();
    }

    @Test
    void scopeEndsWithTheGrantOnTheNextCall() {
        String system = arrange.system();
        String grant = arrange.grant("SMO", "scope.leaver@ci.annualva.test", system);
        var leaver = signedInAs("af-6008", "scope.leaver@ci.annualva.test");
        assertThat(leaver.get("/api/probe/systems/" + system + "/VIEW").status()).isEqualTo(200);

        arrange.revoke(grant);

        assertThat(leaver.get("/api/probe/systems/" + system + "/VIEW").status()).isEqualTo(404);
        assertThat(visible(leaver, "VIEW")).isEmpty();
    }

    @Test
    void aSuspendedRoleLosesItsSystems() {
        String system = arrange.system();
        String grant = arrange.grant("CYBER_MANAGER", "scope.suspended@ci.annualva.test", system);
        var suspended = signedInAs("af-6009", "scope.suspended@ci.annualva.test");
        assertThat(visible(suspended, "REVIEW")).containsExactly(system);

        arrange.suspend(grant);

        assertThat(visible(suspended, "REVIEW")).isEmpty();
    }
}
