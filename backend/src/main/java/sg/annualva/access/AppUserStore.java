package sg.annualva.access;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The app_user table. Constant SQL, named parameters (as-2); emails compared case-insensitively (citext). */
@Repository
public class AppUserStore {

    private static final String COLUMNS = "id, email, sso_id, display_name";

    private static final String FIND_BY_SSO_ID =
            "SELECT " + COLUMNS + " FROM app_user WHERE sso_id = :ssoId";

    private static final String FIND_UNLINKED_BY_EMAIL =
            "SELECT " + COLUMNS + " FROM app_user WHERE email = CAST(:email AS citext) AND sso_id IS NULL";

    private static final String INSERT =
            "INSERT INTO app_user (email, sso_id, display_name, created_at, linked_at) "
            + "VALUES (CAST(:email AS citext), :ssoId, :displayName, :now, :linkedAt) RETURNING id";

    private static final String LINK =
            "UPDATE app_user SET sso_id = :ssoId, display_name = :displayName, linked_at = :now WHERE id = :id";

    /** A changed email follows the person unless another user already holds it. */
    private static final String REFRESH =
            "UPDATE app_user SET display_name = :displayName, "
            + "email = CASE WHEN EXISTS (SELECT 1 FROM app_user other WHERE other.email = CAST(:email AS citext) "
            + "AND other.id <> app_user.id) THEN app_user.email ELSE CAST(:email AS citext) END "
            + "WHERE id = :id";

    private static final String FIND_BY_ID = "SELECT " + COLUMNS + " FROM app_user WHERE id = :id";

    /** Pre-registers a person by email unless someone holds it already, whether signed in or not. */
    private static final String KNOW =
            "INSERT INTO app_user (email, created_at) VALUES (CAST(:email AS citext), :now) ON CONFLICT (email) DO NOTHING";

    private static final String ID_BY_EMAIL = "SELECT id FROM app_user WHERE email = CAST(:email AS citext)";

    /** The identity provider reported the person disabled (ticket 47); the first report's time is kept. */
    private static final String DISABLED =
            "UPDATE app_user SET idp_disabled_at = COALESCE(idp_disabled_at, :now) WHERE id = :id";

    /** The identity provider let the person sign in again. */
    private static final String ENABLED =
            "UPDATE app_user SET idp_disabled_at = NULL WHERE id = :id AND idp_disabled_at IS NOT NULL";

    private static final String SSO_ID = "SELECT sso_id FROM app_user WHERE id = :id AND sso_id IS NOT NULL";

    private final JdbcClient jdbc;

    AppUserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AppUser> findBySsoId(String ssoId) {
        return jdbc.sql(FIND_BY_SSO_ID).param("ssoId", ssoId).query(AppUser.class).optional();
    }

    Optional<AppUser> findUnlinkedByEmail(String email) {
        return jdbc.sql(FIND_UNLINKED_BY_EMAIL).param("email", email).query(AppUser.class).optional();
    }

    /** A user that has signed in: created and linked in one step. */
    AppUser create(String ssoId, String email, @Nullable String displayName, Instant now) {
        UUID id = jdbc.sql(INSERT)
                .param("email", email)
                .param("ssoId", ssoId)
                .param("displayName", displayName)
                .param("now", utc(now))
                .param("linkedAt", utc(now))
                .query(UUID.class)
                .single();
        return byId(id);
    }

    /** A user known only by email (System Register), waiting for the first sign-in to link it. */
    public AppUser preRegister(String email, Instant now) {
        UUID id = jdbc.sql(INSERT)
                .param("email", email)
                .param("ssoId", null)
                .param("displayName", null)
                .param("now", utc(now))
                .param("linkedAt", null)
                .query(UUID.class)
                .single();
        return byId(id);
    }

    /**
     * The user id of the person with this email: the one who signed in with it, or the one pre-registered by it, and when
     * there is neither, a new pre-registration that their first sign-in links (as {@link #preRegister} makes). For
     * something addressed to a person the System Register names before they have signed in (a notification).
     */
    public UUID known(String email, Instant now) {
        jdbc.sql(KNOW).param("email", email).param("now", utc(now)).update();
        return jdbc.sql(ID_BY_EMAIL).param("email", email).query(UUID.class).single();
    }

    AppUser link(UUID id, String ssoId, @Nullable String displayName, Instant now) {
        jdbc.sql(LINK).param("id", id).param("ssoId", ssoId).param("displayName", displayName).param("now", utc(now)).update();
        return byId(id);
    }

    AppUser refresh(UUID id, String email, @Nullable String displayName) {
        jdbc.sql(REFRESH).param("id", id).param("email", email).param("displayName", displayName).update();
        return byId(id);
    }

    void disabled(UUID id, Instant now) {
        jdbc.sql(DISABLED).param("id", id).param("now", utc(now)).update();
    }

    void enabled(UUID id) {
        jdbc.sql(ENABLED).param("id", id).update();
    }

    /** The person's identifier at the identity provider, once they have signed in. */
    Optional<String> ssoIdOf(UUID id) {
        return jdbc.sql(SSO_ID).param("id", id).query(String.class).optional();
    }

    private AppUser byId(UUID id) {
        return jdbc.sql(FIND_BY_ID).param("id", id).query(AppUser.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
