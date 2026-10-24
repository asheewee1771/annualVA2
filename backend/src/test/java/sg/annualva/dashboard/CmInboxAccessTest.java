package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;

/**
 * IM8 ac-1: only a Cyber Manager may read the Cyber Manager's inbox. An SMO/Tech Lead, a VA POC, an Auditor and a person
 * with no role get 403 and learn nothing of the Systems, no session gets 401, and nobody can change the inbox through its
 * address.
 */
class CmInboxAccessTest extends CyberManagerSeam1 {

    private void registerSystem() {
        registered(listed("SYS-CA1", "Access one", SMO_A, CM, "Q3"));
    }

    @Test
    void anSmoAndAVaPocAreForbiddenAndLearnNothingOfTheSystem() {
        registerSystem();

        var smo = signedInAs(SMO_A).get(CM_INBOX);
        var poc = vaPoc().get(CM_INBOX);

        assertThat(smo.status()).isEqualTo(403);
        assertThat(ApiClient.json(smo).get("type").asString()).isEqualTo("https://annualva/problems/forbidden");
        assertThat(smo.body()).doesNotContain("SYS-CA1");
        assertThat(poc.status()).isEqualTo(403);
        assertThat(poc.body()).doesNotContain("SYS-CA1");
        assertThat(signedInAs(SMO_A).get(CM_INBOX + "?cycle=2027").status()).as("whatever the Cycle").isEqualTo(403);
    }

    @Test
    void anAuditorAndAPersonWithNoRoleAreForbiddenAndAnonymousIsUnauthenticated() {
        registerSystem();
        ApiClient auditor = signedInAs("cm.inbox.auditor@ci.annualva.test");
        arrange.grant("AUDITOR", "cm.inbox.auditor@ci.annualva.test", null, "GRANT", "2027-12-31T00:00:00Z");

        assertThat(auditor.get(CM_INBOX).status()).isEqualTo(403);
        assertThat(signedInAs("cm.inbox.nobody@ci.annualva.test").get(CM_INBOX).status()).isEqualTo(403);
        var anonymous = anotherBrowser().get(CM_INBOX);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(ApiClient.json(anonymous).get("type").asString()).isEqualTo("https://annualva/problems/unauthenticated");
    }

    @Test
    void theInboxCannotBeChangedThroughItsAddress() {
        registerSystem();

        assertThat(signedInAs(CM).postJson(CM_INBOX, "{}").status()).isIn(403, 405);
    }

    @Test
    void aCycleTheAppDoesNotRunIsNotFoundAndAValueThatIsNotAYearIsRefusedWithoutEchoingIt() {
        registerSystem();
        ApiClient manager = signedInAs(CM);

        assertThat(manager.get(CM_INBOX + "?cycle=2019").status()).isEqualTo(404);
        var notAYear = manager.get(CM_INBOX + "?cycle=next-year");
        assertThat(notAYear.status()).isEqualTo(400);
        assertThat(notAYear.body()).doesNotContain("next-year");
    }
}
