package sg.annualva.register;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The Register's tables: the Systems it holds, and the previews of changes to it. Constant SQL, named parameters
 * (as-2). It reads the Systems and writes previews; Systems themselves are written by the apply (ticket 10). A
 * preview is stored whole, as one JSON list of {@link PreviewRow}: it is made once, read whole and applied whole, and
 * nothing ever asks a question of one row alone.
 */
@Repository
class RegisterStore {

    private static final String SYSTEMS =
            "SELECT id, code::text AS code, name, smo_email::text AS smo_email, cm_email::text AS cm_email, quarter, "
            + "retired_at IS NOT NULL AS retired FROM va_system ORDER BY code";

    /** The people among these emails (lower-case) who have signed in: a pre-registered person has not. */
    private static final String SIGNED_IN =
            "SELECT lower(email::text) FROM app_user WHERE sso_id IS NOT NULL AND lower(email::text) = ANY(:emails)";

    private static final String INSERT_UPLOAD =
            "INSERT INTO register_upload (created_by, created_at, expires_at, rows) "
            + "VALUES (:createdBy, :createdAt, :expiresAt, CAST(:rows AS jsonb)) RETURNING id";

    private static final String UPLOAD =
            "SELECT id, status, created_at, expires_at, rows::text AS rows FROM register_upload WHERE id = :id";

    private static final TypeReference<List<PreviewRow>> ROWS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    RegisterStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<ExistingSystem> systems() {
        return jdbc.sql(SYSTEMS).query((rs, row) -> new ExistingSystem(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("smo_email"),
                rs.getString("cm_email"), rs.getInt("quarter"), rs.getBoolean("retired"))).list();
    }

    /** Which of these lower-case emails belong to a person who has signed in. */
    Set<String> signedIn(Set<String> emails) {
        if (emails.isEmpty()) {
            return Set.of();
        }
        List<String> found = jdbc.sql(SIGNED_IN).param("emails", emails.toArray(String[]::new)).query(String.class).list();
        return new HashSet<>(found);
    }

    /** Stores a new preview and returns its id; the rows hold only sanitised text (see {@link PreviewBuilder#display}). */
    UUID insertUpload(UUID createdBy, Instant createdAt, Instant expiresAt, List<PreviewRow> rows) {
        return jdbc.sql(INSERT_UPLOAD)
                .param("createdBy", createdBy)
                .param("createdAt", utc(createdAt))
                .param("expiresAt", utc(expiresAt))
                .param("rows", json.writeValueAsString(rows))
                .query(UUID.class)
                .single();
    }

    Optional<Preview> preview(UUID id) {
        return jdbc.sql(UPLOAD).param("id", id).query((rs, row) -> new Preview(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                json.readValue(rs.getString("rows"), ROWS))).optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
