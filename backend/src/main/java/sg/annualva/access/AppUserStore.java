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

    AppUser link(UUID id, String ssoId, @Nullable String displayName, Instant now) {
        jdbc.sql(LINK).param("id", id).param("ssoId", ssoId).param("displayName", displayName).param("now", utc(now)).update();
        return byId(id);
    }

    AppUser refresh(UUID id, String email, @Nullable String displayName) {
        jdbc.sql(REFRESH).param("id", id).param("email", email).param("displayName", displayName).update();
        return byId(id);
    }

    private AppUser byId(UUID id) {
        return jdbc.sql(FIND_BY_ID).param("id", id).query(AppUser.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
