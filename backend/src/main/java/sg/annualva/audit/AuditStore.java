package sg.annualva.audit;

import static sg.annualva.jdbc.Timestamps.utc;
import static sg.annualva.jdbc.Timestamps.utcOrNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The audit_entry table: inserts and reads, nothing else. There is no update and no delete here, and the table
 * would refuse one (a trigger, V5). Constant SQL, named parameters (as-2).
 */
@Repository
class AuditStore {

    private static final String COLUMNS = "id, occurred_at, actor_id, actor_role, action, outcome, system_id, "
            + "submission_id, finding_id, correlation_id, detail::text AS detail";

    private static final String INSERT = "INSERT INTO audit_entry (occurred_at, actor_id, actor_role, action, outcome, "
            + "system_id, submission_id, finding_id, correlation_id, detail) VALUES (:occurredAt, :actorId, :actorRole, "
            + ":action, :outcome, :systemId, :submissionId, :findingId, :correlationId, CAST(:detail AS jsonb)) "
            + "RETURNING " + COLUMNS;

    /** Every filter is optional: a null parameter does not narrow. */
    private static final String FILTERED = " FROM audit_entry "
            + "WHERE (CAST(:actorId AS uuid) IS NULL OR actor_id = CAST(:actorId AS uuid)) "
            + "AND (CAST(:action AS text) IS NULL OR action = CAST(:action AS text)) "
            + "AND (CAST(:systemId AS uuid) IS NULL OR system_id = CAST(:systemId AS uuid)) "
            + "AND (CAST(:submissionId AS uuid) IS NULL OR submission_id = CAST(:submissionId AS uuid)) "
            + "AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= CAST(:from AS timestamptz)) "
            + "AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < CAST(:to AS timestamptz)) "
            + "AND (CAST(:within AS uuid[]) IS NULL OR submission_id = ANY(CAST(:within AS uuid[])))";

    private static final String PAGE = "SELECT " + COLUMNS + FILTERED
            + " ORDER BY occurred_at DESC, seq DESC LIMIT :limit OFFSET :offset";

    private static final String COUNT = "SELECT count(*)" + FILTERED;

    private static final TypeReference<Map<String, Object>> DETAIL = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AuditStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    AuditEntry insert(AuditEvent event, Instant occurredAt, String correlationId) {
        return jdbc.sql(INSERT)
                .param("occurredAt", utc(occurredAt))
                .param("actorId", event.actorId())
                .param("actorRole", event.actorRole())
                .param("action", event.action().name())
                .param("outcome", event.outcome().name())
                .param("systemId", event.systemId())
                .param("submissionId", event.submissionId())
                .param("findingId", event.findingId())
                .param("correlationId", correlationId)
                .param("detail", json.writeValueAsString(event.detail().values()))
                .query(this::map)
                .single();
    }

    List<AuditEntry> page(AuditFilter filter, int limit, long offset) {
        return filtered(jdbc.sql(PAGE), filter).param("limit", limit).param("offset", offset).query(this::map).list();
    }

    long count(AuditFilter filter) {
        return filtered(jdbc.sql(COUNT), filter).query(Long.class).single();
    }

    private static JdbcClient.StatementSpec filtered(JdbcClient.StatementSpec statement, AuditFilter filter) {
        return statement
                .param("actorId", filter.actorId())
                .param("action", filter.action() == null ? null : filter.action().name())
                .param("systemId", filter.systemId())
                .param("submissionId", filter.submissionId())
                .param("from", utcOrNull(filter.from()))
                .param("to", utcOrNull(filter.to()))
                .param("within", ids(filter.within()));
    }

    private AuditEntry map(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_role"),
                AuditAction.valueOf(rs.getString("action")),
                AuditOutcome.valueOf(rs.getString("outcome")),
                rs.getObject("system_id", UUID.class),
                rs.getObject("submission_id", UUID.class),
                rs.getObject("finding_id", UUID.class),
                rs.getString("correlation_id"),
                json.readValue(rs.getString("detail"), DETAIL));
    }

    private static String @Nullable [] ids(@Nullable Set<UUID> ids) {
        return ids == null ? null : ids.stream().map(UUID::toString).toArray(String[]::new);
    }

}
