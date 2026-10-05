package sg.annualva.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * Story 84, IM8 lm-4: nobody can change or delete an entry. This is the one test that goes under the HTTP API, because
 * the claim is about the application's own database connection: whatever the application's code or a bug in it tried,
 * the database refuses.
 */
class AuditAppendOnlyTest extends Seam1Test {

    @Autowired
    private JdbcClient application;

    @Test
    void anUpdateThroughTheApplicationsConnectionFails() {
        anEntryExists();
        String before = fingerprint();

        assertThatThrownBy(() -> application.sql("UPDATE audit_entry SET action = 'SIGN_OUT'").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> application.sql("UPDATE audit_entry SET outcome = 'SUCCESS' WHERE id = gen_random_uuid()")
                .update())
                .as("even a statement that matches no row")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(fingerprint()).as("nothing was changed or removed").isEqualTo(before);
    }

    @Test
    void aDeleteThroughTheApplicationsConnectionFails() {
        anEntryExists();
        String before = fingerprint();

        assertThatThrownBy(() -> application.sql("DELETE FROM audit_entry").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> application.sql("DELETE FROM audit_entry WHERE id = gen_random_uuid()").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(fingerprint()).as("nothing was changed or removed").isEqualTo(before);
    }

    @Test
    void aTruncateThroughTheApplicationsConnectionFails() {
        anEntryExists();
        String before = fingerprint();

        assertThatThrownBy(() -> application.sql("TRUNCATE audit_entry").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(fingerprint()).as("nothing was changed or removed").isEqualTo(before);
    }

    @Test
    void theDatabaseRefusesDetailThatIsNotOnTheAllowList() {
        assertThatThrownBy(() -> application.sql("INSERT INTO audit_entry (occurred_at, action, outcome, correlation_id, "
                + "detail) VALUES (now(), 'REQUEST_REFUSED', 'REFUSED', 'none', '{\"title\": \"CVE-9999-SENTINEL\"}')")
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_entry_detail_check");
        assertThatThrownBy(() -> application.sql("INSERT INTO audit_entry (occurred_at, action, outcome, correlation_id, "
                + "detail) VALUES (now(), 'REQUEST_REFUSED', 'REFUSED', 'none', '[\"reason\"]')")
                .update())
                .isInstanceOf(DataAccessException.class);
    }

    private void anEntryExists() {
        SignIn.signedIn(anotherBrowser(), issuer, TestIdentity.of("af-a701", "append.only@ci.annualva.test", "Append Only"));
    }

    /** How many entries there are and a digest of what they say, so a change to any one shows. */
    private String fingerprint() {
        return application.sql("SELECT count(*) || md5(coalesce(string_agg(id::text || action || outcome || detail::text, ',' "
                + "ORDER BY id), '')) FROM audit_entry").query(String.class).single();
    }
}
