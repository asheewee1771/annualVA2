package sg.annualva.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;

/**
 * IM8 ac-1: only an SMO/Tech Lead may read the SMO inbox. A Cyber Manager, a VA POC, an Auditor and a person with no
 * role get 403, no session gets 401, and nobody can change the inbox through its address.
 */
class SmoInboxAccessTest extends InboxSeam1 {

    private void registerSystem() {
        registered(listed("SYS-A1", "Access one", SMO_A, CM, "Q3"));
    }

    @Test
    void aCyberManagerAndAVaPocAreForbiddenAndLearnNothingOfTheSystem() {
        registerSystem();

        var manager = signedInAs(CM).get(INBOX);
        var poc = vaPoc().get(INBOX);

        assertThat(manager.status()).isEqualTo(403);
        assertThat(ApiClient.json(manager).get("type").asString()).isEqualTo("https://annualva/problems/forbidden");
        assertThat(manager.body()).doesNotContain("SYS-A1");
        assertThat(poc.status()).isEqualTo(403);
        assertThat(poc.body()).doesNotContain("SYS-A1");
        assertThat(signedInAs(CM).get(INBOX + "?cycle=2027").status()).as("whatever the Cycle").isEqualTo(403);
    }

    @Test
    void anAuditorAndAPersonWithNoRoleAreForbiddenAndAnonymousIsUnauthenticated() {
        registerSystem();
        ApiClient auditor = signedInAs("inbox.auditor@ci.annualva.test");
        arrange.grant("AUDITOR", "inbox.auditor@ci.annualva.test", null, "GRANT", "2027-12-31T00:00:00Z");

        assertThat(auditor.get(INBOX).status()).isEqualTo(403);
        assertThat(signedInAs("inbox.nobody@ci.annualva.test").get(INBOX).status()).isEqualTo(403);
        var anonymous = anotherBrowser().get(INBOX);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(ApiClient.json(anonymous).get("type").asString()).isEqualTo("https://annualva/problems/unauthenticated");
    }

    @Test
    void theInboxCannotBeChangedThroughItsAddress() {
        registerSystem();
        ApiClient smo = signedInAs(SMO_A);

        assertThat(smo.postJson(INBOX, "{}").status()).isIn(403, 405);
    }
}
