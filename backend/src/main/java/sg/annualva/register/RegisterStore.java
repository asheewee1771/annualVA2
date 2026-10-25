package sg.annualva.register;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The Register's tables: the Systems it holds, and the previews of changes to it. Constant SQL, named parameters
 * (as-2). A preview is stored whole, as one JSON list of {@link PreviewRow}: it is made once, read whole and applied
 * whole, and nothing ever asks a question of one row alone. Systems are written only by the apply.
 */
@Repository
class RegisterStore {

    private static final String SYSTEM_COLUMNS =
            "SELECT id, code::text AS code, name, smo_email::text AS smo_email, cm_email::text AS cm_email, quarter, "
            + "retired_at IS NOT NULL AS retired, smo_unreachable_at FROM va_system ";

    private static final String SYSTEMS = SYSTEM_COLUMNS + "ORDER BY code";

    private static final String SYSTEM = SYSTEM_COLUMNS + "WHERE id = :id";

    private static final String RETIRED_BEFORE = SYSTEM_COLUMNS + "WHERE retired_at < :before ORDER BY code";

    /**
     * Which of these System IDs are the same System, by the column's own citext rules: each value with the smallest of
     * the values equal to it, so two IDs share a key exactly when citext says they are equal.
     */
    private static final String FOLD =
            "SELECT a.v AS value, MIN(b.v) AS key FROM unnest(CAST(:values AS text[])) AS a(v) "
            + "JOIN unnest(CAST(:values AS text[])) AS b(v) ON CAST(a.v AS citext) = CAST(b.v AS citext) GROUP BY a.v";

    /** The people among these emails (lower-case) who have signed in: a pre-registered person has not. */
    private static final String SIGNED_IN =
            "SELECT lower(email::text) FROM app_user WHERE sso_id IS NOT NULL AND lower(email::text) = ANY(:emails)";

    private static final String INSERT_UPLOAD =
            "INSERT INTO register_upload (created_by, created_at, expires_at, rows) "
            + "VALUES (:createdBy, :createdAt, :expiresAt, CAST(:rows AS jsonb)) RETURNING id";

    private static final String UPLOAD =
            "SELECT id, status, created_at, expires_at, rows::text AS rows FROM register_upload WHERE id = :id";

    /** One apply at a time: the second waits, then finds the baseline its preview was made from has changed. */
    private static final String LOCK_APPLY = "SELECT pg_advisory_xact_lock(hashtext('annualva.register-apply'))";

    /**
     * Keeps a Cycle's Systems as the Register holds them now: the active Systems and their Quarters, once (ADR 0044). The
     * caller holds the apply lock, so no apply lands between the two statements.
     */
    private static final String FREEZE =
            "INSERT INTO cycle_register (cycle_year, frozen_at) VALUES (:cycle, :now) ON CONFLICT (cycle_year) DO NOTHING";

    private static final String FREEZE_SYSTEMS =
            "INSERT INTO cycle_register_system (cycle_year, system_id, quarter) "
            + "SELECT :cycle, id, quarter FROM va_system WHERE retired_at IS NULL";

    private static final String FROZEN = "SELECT 1 FROM cycle_register WHERE cycle_year = :cycle";

    private static final String FROZEN_SYSTEMS =
            "SELECT system_id, quarter FROM cycle_register_system WHERE cycle_year = :cycle";

    private static final String CODES_TAKEN =
            "SELECT code::text FROM va_system WHERE code = ANY(CAST(:codes AS citext[]))";

    private static final String INSERT_SYSTEM =
            "INSERT INTO va_system (code, name, smo_email, cm_email, quarter, added_on) "
            + "VALUES (CAST(:code AS citext), :name, CAST(:smo AS citext), CAST(:cm AS citext), :quarter, :addedOn) "
            + "RETURNING id";

    /** A System the file lists again is current again: an update also reinstates a retired one. */
    private static final String UPDATE_SYSTEM =
            "UPDATE va_system SET name = :name, smo_email = CAST(:smo AS citext), cm_email = CAST(:cm AS citext), "
            + "quarter = :quarter, retired_at = NULL WHERE id = :id";

    private static final String RETIRE_SYSTEM =
            "UPDATE va_system SET retired_at = :now WHERE id = :id AND retired_at IS NULL";

    /**
     * Flags SMO Unreachable every active System naming this address as its SMO/Tech Lead that is not flagged already, so a
     * flag keeps the time of the first refusal; returns the Systems it flagged.
     */
    private static final String FLAG_UNREACHABLE =
            "UPDATE va_system SET smo_unreachable_at = :at WHERE smo_email = CAST(:email AS citext) "
            + "AND retired_at IS NULL AND smo_unreachable_at IS NULL RETURNING id";

    /**
     * Clears the flag of every System naming this address that was set no later than a mail the relay took, so a refusal
     * recorded after that mail stands; returns the Systems it cleared.
     */
    private static final String CLEAR_REACHED =
            "UPDATE va_system SET smo_unreachable_at = NULL WHERE smo_email = CAST(:email AS citext) "
            + "AND smo_unreachable_at <= :at RETURNING id";

    private static final String CLEAR_SYSTEM =
            "UPDATE va_system SET smo_unreachable_at = NULL WHERE id = :id AND smo_unreachable_at IS NOT NULL";

    private static final String MARK_APPLIED =
            "UPDATE register_upload SET status = 'APPLIED', applied_at = :now, applied_by = :by "
            + "WHERE id = :id AND status = 'PREVIEW'";

    private static final TypeReference<List<PreviewRow>> ROWS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    RegisterStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<ExistingSystem> systems() {
        return jdbc.sql(SYSTEMS).query((rs, row) -> existing(rs)).list();
    }

    /** The Systems retired before this instant, in System ID order. */
    List<ExistingSystem> retiredBefore(Instant before) {
        return jdbc.sql(RETIRED_BEFORE).param("before", before.atOffset(java.time.ZoneOffset.UTC))
                .query((rs, row) -> existing(rs)).list();
    }

    /** One System, retired or not. */
    Optional<ExistingSystem> system(UUID id) {
        return jdbc.sql(SYSTEM).param("id", id).query((rs, row) -> existing(rs)).optional();
    }

    private static ExistingSystem existing(ResultSet rs) throws SQLException {
        return new ExistingSystem(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("smo_email"), rs.getString("cm_email"), rs.getInt("quarter"), rs.getBoolean("retired"),
                instant(rs.getObject("smo_unreachable_at", OffsetDateTime.class)));
    }

    /**
     * The key each of these System IDs is matched by: two IDs have the same key exactly when the Register's citext
     * column holds them as equal. An ID not asked about has no key here; the caller folds only what it asked.
     */
    Map<String, String> foldedIds(Collection<String> ids) {
        Map<String, String> keys = new HashMap<>();
        if (ids.isEmpty()) {
            return keys;
        }
        jdbc.sql(FOLD).param("values", ids.toArray(String[]::new))
                .query(rs -> {
                    keys.put(rs.getString("value"), rs.getString("key"));
                });
        return keys;
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

    /** Takes the lock that makes applies one at a time; held until the transaction ends. */
    void lockApply() {
        jdbc.sql(LOCK_APPLY).query().singleRow();
    }

    /** Which of these System IDs the Register already holds, by its citext rules (retired or not). */
    Set<String> codesTaken(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql(CODES_TAKEN).param("codes", codes.toArray(String[]::new)).query(String.class).list());
    }

    UUID insertSystem(String code, String name, String smoEmail, String cyberManagerEmail, int quarter, LocalDate addedOn) {
        return jdbc.sql(INSERT_SYSTEM)
                .param("code", code)
                .param("name", name)
                .param("smo", smoEmail)
                .param("cm", cyberManagerEmail)
                .param("quarter", quarter)
                .param("addedOn", addedOn)
                .query(UUID.class)
                .single();
    }

    void updateSystem(UUID id, String name, String smoEmail, String cyberManagerEmail, int quarter) {
        jdbc.sql(UPDATE_SYSTEM)
                .param("id", id)
                .param("name", name)
                .param("smo", smoEmail)
                .param("cm", cyberManagerEmail)
                .param("quarter", quarter)
                .update();
    }

    void retireSystem(UUID id, Instant now) {
        jdbc.sql(RETIRE_SYSTEM).param("id", id).param("now", utc(now)).update();
    }

    /** Flags SMO Unreachable the active Systems naming this address and not flagged yet; their ids. */
    List<UUID> flagUnreachable(String email, Instant at) {
        return jdbc.sql(FLAG_UNREACHABLE).param("email", email).param("at", utc(at)).query(UUID.class).list();
    }

    /** Clears the flags of the Systems naming this address that were set no later than {@code at}; their ids. */
    List<UUID> clearReached(String email, Instant at) {
        return jdbc.sql(CLEAR_REACHED).param("email", email).param("at", utc(at)).query(UUID.class).list();
    }

    /** Clears one System's flag; whether it was flagged. */
    boolean clearUnreachable(UUID id) {
        return jdbc.sql(CLEAR_SYSTEM).param("id", id).update() == 1;
    }

    /** Marks the preview applied; false when it was not waiting any more. */
    boolean markApplied(UUID id, Instant now, UUID by) {
        return jdbc.sql(MARK_APPLIED).param("id", id).param("now", utc(now)).param("by", by).update() == 1;
    }

    /** Keeps the Cycle's Systems as the Register holds them now, unless they are kept already; whether this kept them. */
    boolean freeze(int cycleYear, Instant now) {
        if (jdbc.sql(FREEZE).param("cycle", cycleYear).param("now", utc(now)).update() == 0) {
            return false;
        }
        jdbc.sql(FREEZE_SYSTEMS).param("cycle", cycleYear).update();
        return true;
    }

    /** The Quarter of each System the Cycle kept, by System; nothing while the Cycle follows the Register as it is. */
    Optional<Map<UUID, Integer>> frozen(int cycleYear) {
        if (jdbc.sql(FROZEN).param("cycle", cycleYear).query().listOfRows().isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, Integer> quarters = new HashMap<>();
        jdbc.sql(FROZEN_SYSTEMS).param("cycle", cycleYear)
                .query(rs -> {
                    quarters.put(rs.getObject("system_id", UUID.class), rs.getInt("quarter"));
                });
        return Optional.of(quarters);
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
