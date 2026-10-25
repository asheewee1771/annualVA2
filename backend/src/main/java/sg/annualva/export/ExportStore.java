package sg.annualva.export;

import static sg.annualva.jdbc.Timestamps.instant;
import static sg.annualva.jdbc.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * The exports table (V32). Constant SQL, named parameters (as-2). What a queued export will hold is kept whole as one
 * JSON document, as a Register preview is: it is written once, read whole by the export job and removed once the file is
 * made or the export fails. Each move of an export is guarded by the status it moves from, so two passes of the job that
 * reach the same export change it once.
 */
@Repository
class ExportStore {

    /** One export as its requester reads it. */
    record Export(
            UUID id, UUID requestedBy, Instant requestedAt, ExportType type, ExportFormat format, int cycle,
            @Nullable Integer quarter, Classification classification, int systemCount, ExportStatus status,
            @Nullable String fileKey, @Nullable Instant readyAt, @Nullable Instant expiresAt) {}

    /** A queued export with what it will hold, as stored: read back with {@link #summary}. */
    record Queued(UUID id, ExportFormat format, String content) {}

    /** A ready export whose 24 hours are up, with the key of its file. */
    record Expiring(UUID id, String fileKey) {}

    private static final String COLUMNS =
            "SELECT id, requested_by, requested_at, export_type, format, cycle, quarter, classification, system_count, "
            + "status, file_key, ready_at, expires_at FROM export_request ";

    private static final String INSERT =
            "INSERT INTO export_request (requested_by, requested_at, export_type, format, cycle, quarter, classification, "
            + "system_count, content) VALUES (:requestedBy, :requestedAt, :type, :format, :cycle, :quarter, "
            + ":classification, :systemCount, CAST(:content AS jsonb)) RETURNING id";

    private static final String FIND = COLUMNS + "WHERE id = :id";

    private static final String MINE =
            COLUMNS + "WHERE requested_by = :user AND requested_at > :since ORDER BY requested_at DESC, id LIMIT 50";

    private static final String QUEUED =
            "SELECT id, format, content::text AS content FROM export_request WHERE status = 'QUEUED' "
            + "ORDER BY requested_at, id LIMIT :limit";

    private static final String READY =
            "UPDATE export_request SET status = 'READY', content = NULL, file_key = :key, ready_at = :readyAt, "
            + "expires_at = :expiresAt WHERE id = :id AND status = 'QUEUED'";

    private static final String FAILED =
            "UPDATE export_request SET status = 'FAILED', content = NULL WHERE id = :id AND status = 'QUEUED'";

    private static final String EXPIRING =
            "SELECT id, file_key FROM export_request WHERE status = 'READY' AND expires_at <= :now "
            + "ORDER BY expires_at, id LIMIT :limit";

    private static final String EXPIRED =
            "UPDATE export_request SET status = 'EXPIRED', file_key = NULL WHERE id = :id AND status = 'READY'";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ExportStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    UUID insert(UUID requestedBy, Instant requestedAt, ExportType type, ExportFormat format, ManagementSummary summary) {
        return jdbc.sql(INSERT)
                .param("requestedBy", requestedBy)
                .param("requestedAt", utc(requestedAt))
                .param("type", type.name())
                .param("format", format.name())
                .param("cycle", summary.cycle())
                .param("quarter", summary.quarter())
                .param("classification", summary.classification().name())
                .param("systemCount", summary.systems().size())
                .param("content", json.writeValueAsString(summary))
                .query(UUID.class)
                .single();
    }

    Optional<Export> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query(ExportStore::export).optional();
    }

    /** The person's exports asked for after this instant, newest first. */
    List<Export> mine(UUID user, Instant since) {
        return jdbc.sql(MINE).param("user", user).param("since", utc(since))
                .query(ExportStore::export).list();
    }

    /** The oldest queued exports. */
    List<Queued> queued(int limit) {
        return jdbc.sql(QUEUED).param("limit", limit)
                .query((rs, row) -> new Queued(rs.getObject("id", UUID.class), ExportFormat.valueOf(rs.getString("format")),
                        rs.getString("content")))
                .list();
    }

    /**
     * What a queued export will hold, read back from what was stored.
     *
     * @throws RuntimeException when it cannot be read (the export job then fails that export alone)
     */
    ManagementSummary summary(Queued queued) {
        return json.readValue(queued.content(), ManagementSummary.class);
    }

    /** @return whether this call made it ready (false when another pass already moved it) */
    boolean ready(UUID id, String key, Instant readyAt, Instant expiresAt) {
        return jdbc.sql(READY).param("id", id).param("key", key).param("readyAt", utc(readyAt))
                .param("expiresAt", utc(expiresAt)).update() == 1;
    }

    void failed(UUID id) {
        jdbc.sql(FAILED).param("id", id).update();
    }

    /** The ready exports whose 24 hours are up at this instant, the longest expired first. */
    List<Expiring> expiring(Instant now, int limit) {
        return jdbc.sql(EXPIRING).param("now", utc(now)).param("limit", limit)
                .query((rs, row) -> new Expiring(rs.getObject("id", UUID.class), rs.getString("file_key")))
                .list();
    }

    void expired(UUID id) {
        jdbc.sql(EXPIRED).param("id", id).update();
    }

    private static Export export(ResultSet rs, int row) throws SQLException {
        return new Export(rs.getObject("id", UUID.class), rs.getObject("requested_by", UUID.class),
                rs.getObject("requested_at", OffsetDateTime.class).toInstant(), ExportType.valueOf(rs.getString("export_type")),
                ExportFormat.valueOf(rs.getString("format")), rs.getInt("cycle"), rs.getObject("quarter", Integer.class),
                Classification.valueOf(rs.getString("classification")), rs.getInt("system_count"),
                ExportStatus.valueOf(rs.getString("status")), rs.getString("file_key"), instant(rs, "ready_at"),
                instant(rs, "expires_at"));
    }

}
