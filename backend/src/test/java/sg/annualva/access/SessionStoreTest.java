package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * Sessions live in PostgreSQL, not in the application's memory, so they survive a restart (ADR 0011),
 * and no token is kept in them (story 72).
 */
class SessionStoreTest extends Seam1Test {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void signedInSessionIsHeldInTheDatabaseKeyedByUserIdAndEndsWithSignOut() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-3001", "stored@ci.annualva.test", "Stored"));
        String userId = ApiClient.json(api.get("/api/session")).get("uuid").asString();

        assertThat(storedSessions(userId)).as("the session is in the store").isEqualTo(1);

        api.withCsrf().post("/api/logout");
        assertThat(storedSessions(userId)).as("sign-out removes it").isZero();
    }

    @Test
    void tokensAreNotKeptInTheStoredSession() {
        SignIn.signedIn(api, issuer, TestIdentity.of("af-3002", "tokenless@ci.annualva.test", "Tokenless"));
        String userId = ApiClient.json(api.get("/api/session")).get("uuid").asString();

        List<String> stored = jdbc.sql("""
                SELECT a.attribute_bytes FROM spring_session_attributes a
                JOIN spring_session s ON s.primary_id = a.session_primary_id
                WHERE s.principal_name = :userId
                """)
                .param("userId", userId)
                .query(byte[].class)
                .list()
                .stream()
                .map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1))
                .toList();

        assertThat(stored).isNotEmpty();
        assertThat(stored).allSatisfy(attribute -> assertThat(attribute)
                .doesNotContain("eyJ")
                .doesNotContain("OidcIdToken")
                .doesNotContain("OAuth2AccessToken")
                .doesNotContain("OAuth2AuthorizedClient"));
    }

    private int storedSessions(String userId) {
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
    }
}
