package sg.annualva.retention;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.boundary.FileStore;

/**
 * The disposal lists (V149) and what they are computed from. Constant SQL, named parameters (as-2). Every move of a list
 * is guarded by the status it moves from, so two passes of the job, or two VA POCs, change it once.
 */
@Repository
class DisposalStore {

    /** A list as stored. */
    record ListRow(
            UUID id, ListStatus status, LocalDate computedOn, Instant createdAt, @Nullable Instant cutoff,
            String fingerprint, @Nullable UUID approvedBy, @Nullable Instant approvedAt, @Nullable Instant executedAt) {}

    private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtext('annualva.disposal'))";

    private static final String LIST_COLUMNS = "SELECT id, status, computed_on, created_at, cutoff, fingerprint, "
            + "approved_by, approved_at, executed_at FROM disposal_list ";

    private static final String OPEN = LIST_COLUMNS + "WHERE status IN ('DRAFT', 'APPROVED')";

    private static final String NEWEST = LIST_COLUMNS + "ORDER BY seq DESC LIMIT :limit";

    private static final String FIND = LIST_COLUMNS + "WHERE id = :id";

    private static final String ITEMS = "SELECT kind, target_id, system_id, cycle_year, reason, entries FROM disposal_item "
            + "WHERE list_id = :list ORDER BY seq";

    private static final String INSERT_LIST = "INSERT INTO disposal_list (status, computed_on, created_at, cutoff, "
            + "fingerprint) VALUES ('DRAFT', :computedOn, :now, :cutoff, :fingerprint) RETURNING id";

    private static final String INSERT_ITEM = "INSERT INTO disposal_item (list_id, kind, target_id, system_id, "
            + "cycle_year, reason, entries) VALUES (:list, :kind, :target, :system, :cycle, :reason, :entries)";

    private static final String SUPERSEDE = "UPDATE disposal_list SET status = 'SUPERSEDED' WHERE id = :id "
            + "AND status = 'DRAFT'";

    /** A record due, with the System it is about and, for a superseded file, its VA Submission's Cycle. */
    record Due(UUID id, UUID systemId, int cycle) {}

    private static final String SUBMISSIONS_OF = "SELECT id, system_id, cycle_year FROM va_submission "
            + "WHERE cycle_year = :cycle ORDER BY started_at NULLS LAST, id";

    private static final String FILES_OF = "SELECT f.id, s.system_id, s.cycle_year FROM report_file f "
            + "JOIN va_submission s ON s.id = f.submission_id WHERE s.cycle_year = :cycle ORDER BY f.seq";

    private static final String CYCLE_HOLDS = "SELECT EXISTS (SELECT 1 FROM va_submission WHERE cycle_year = :cycle) "
            + "OR EXISTS (SELECT 1 FROM not_started_overdue WHERE cycle_year = :cycle) "
            + "OR EXISTS (SELECT 1 FROM cycle_register WHERE cycle_year = :cycle)";

    /** A superseded file whose content is still held, of an Endorsed VA Submission. */
    private static final String SUPERSEDED_DUE = "SELECT f.id, s.system_id, s.cycle_year FROM report_file f "
            + "JOIN va_submission s ON s.id = f.submission_id WHERE f.superseded_by IS NOT NULL "
            + "AND f.content_disposed_at IS NULL AND s.state = 'ENDORSED' ORDER BY f.seq";

    /** The Cycles a System still has a VA Submission or a kept Register row in. */
    private static final String CYCLES_OF_SYSTEM = "SELECT cycle_year FROM va_submission WHERE system_id = :system "
            + "UNION SELECT cycle_year FROM cycle_register_system WHERE system_id = :system";

    private static final String AUDIT_BEFORE = "SELECT count(*) FROM audit_entry WHERE occurred_at < :before";

    private static final String APPROVE ="UPDATE disposal_list SET status = 'APPROVED', approved_by = :by, "
            + "approved_at = :now WHERE id = :id AND status = 'DRAFT'";

    /** An export whose 24 hours are up (the export job has removed its file), or one that failed a day ago or more. */
    private static final String EXPIRED_EXPORTS = "SELECT id FROM export_request WHERE status = 'EXPIRED' "
            + "OR (status = 'FAILED' AND requested_at <= :dayAgo) ORDER BY requested_at, id";

    // ---- executing an approved list: every statement reads what to remove from the list's own items ----

    /** A file-store object to remove before the records: its zone and key. */
    record Blob(FileStore.Zone zone, String key) {}

    /** What one execution removed. */
    record Counts(int submissions, int files, int supersededFiles, int exports, int systems, int auditEntries) {}

    private static final String S = "(SELECT target_id FROM disposal_item WHERE list_id = :list AND kind = 'SUBMISSION')";
    private static final String SF =
            "(SELECT target_id FROM disposal_item WHERE list_id = :list AND kind = 'SUPERSEDED_FILE')";
    private static final String E = "(SELECT target_id FROM disposal_item WHERE list_id = :list AND kind = 'EXPORT')";
    private static final String SYS = "(SELECT target_id FROM disposal_item WHERE list_id = :list AND kind = 'SYSTEM')";
    private static final String Y = "(SELECT cycle_year FROM disposal_item WHERE list_id = :list AND kind = 'CYCLE')";
    private static final String CUTOFF = "(SELECT cutoff FROM disposal_list WHERE id = :list)";
    private static final String FINDINGS = "(SELECT id FROM finding WHERE submission_id IN " + S + ")";
    /** The notifications about a disposed VA Submission or System, and those about no VA Submission before the cutoff. */
    private static final String NOTIFICATIONS = "(SELECT id FROM notification WHERE submission_id IN " + S
            + " OR system_id IN " + SYS + " OR (submission_id IS NULL AND created_at < " + CUTOFF + "))";

    private static final String BLOBS = "SELECT 'CLEAN' AS zone, f.clean_key AS key FROM report_file f "
            + "WHERE (f.submission_id IN " + S + " OR f.id IN " + SF + ") AND f.clean_key IS NOT NULL "
            + "AND f.content_disposed_at IS NULL "
            + "UNION ALL SELECT 'QUARANTINE', f.quarantine_key FROM report_file f WHERE f.submission_id IN " + S
            + " AND f.quarantine_key IS NOT NULL "
            + "UNION ALL SELECT 'EXPORT', e.file_key FROM export_request e WHERE e.id IN " + E
            + " AND e.file_key IS NOT NULL";

    private static final String APPROVED = LIST_COLUMNS + "WHERE status = 'APPROVED'";

    private static final String RUNNING = "SELECT set_config('annualva.disposal_list', CAST(:list AS text), true)";

    /**
     * The records in foreign-key order (ADR 0048): what names a record goes before it. The proposals, batches, reviews,
     * returns, Declarations and audit entries are append-only; V149 lets these deletes through only while the list named
     * by {@link #RUNNING} is APPROVED.
     */
    private static final String[] BEFORE_FILES = {
        "DELETE FROM mail_outbox WHERE notification_id IN " + NOTIFICATIONS,
        "DELETE FROM notification WHERE id IN " + NOTIFICATIONS,
        "DELETE FROM submission_return_item WHERE return_id IN (SELECT id FROM submission_return WHERE submission_id IN "
                + S + ")",
        "DELETE FROM submission_return WHERE submission_id IN " + S,
        "DELETE FROM finding_review WHERE finding_id IN " + FINDINGS,
        "DELETE FROM batch_review WHERE batch_id IN (SELECT id FROM bulk_batch WHERE submission_id IN " + S + ")",
        "DELETE FROM report_review WHERE submission_id IN " + S,
        "UPDATE finding SET current_proposal_id = NULL, review_state = NULL WHERE submission_id IN " + S
                + " AND current_proposal_id IS NOT NULL",
        "DELETE FROM finding_status_proposal WHERE finding_id IN " + FINDINGS,
        "DELETE FROM bulk_batch WHERE submission_id IN " + S,
        "DELETE FROM finding_occurrence WHERE finding_id IN " + FINDINGS,
        "DELETE FROM finding WHERE submission_id IN " + S,
        "DELETE FROM report_file_stage WHERE file_id IN (SELECT id FROM report_file WHERE submission_id IN " + S + ")",
    };

    /** One statement for every file of the VA Submissions, so a rescan and the file it replaced go together. */
    private static final String FILES = "DELETE FROM report_file WHERE submission_id IN " + S;

    private static final String[] BEFORE_SUBMISSIONS = {
        "DELETE FROM report_slot WHERE submission_id IN " + S,
        "DELETE FROM declaration WHERE submission_id IN " + S,
        "DELETE FROM submission_participation WHERE submission_id IN " + S,
    };

    private static final String SUBMISSIONS = "DELETE FROM va_submission WHERE id IN " + S;

    /** A Cycle's records about no one VA Submission, and what still names a retired System, before the System. */
    private static final String[] BEFORE_SYSTEMS = {
        "DELETE FROM not_started_overdue WHERE cycle_year IN " + Y + " OR system_id IN " + SYS,
        "DELETE FROM cycle_register_system WHERE cycle_year IN " + Y + " OR system_id IN " + SYS,
        "DELETE FROM cycle_register WHERE cycle_year IN " + Y,
        "DELETE FROM register_upload WHERE created_at < " + CUTOFF,
        "DELETE FROM role_grant WHERE system_id IN " + SYS,
    };

    private static final String SYSTEMS = "DELETE FROM va_system WHERE id IN " + SYS;

    private static final String EXPORTS = "DELETE FROM export_request WHERE id IN " + E;

    private static final String AUDIT = "DELETE FROM audit_entry WHERE occurred_at < " + CUTOFF;

    private static final String SUPERSEDED = "UPDATE report_file SET content_disposed_at = :now WHERE id IN " + SF
            + " AND content_disposed_at IS NULL";

    private static final String EXECUTED = "UPDATE disposal_list SET status = 'EXECUTED', executed_at = :now "
            + "WHERE id = :list AND status = 'APPROVED'";

    private final JdbcClient jdbc;

    DisposalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Holds every other listing, approval and execution off until this transaction ends, so two instances of the job, or
     * the job and a VA POC's approval, never act on the lists at once.
     */
    void lock() {
        jdbc.sql(LOCK).query().singleRow();
    }

    /** The list that is waiting: a DRAFT for a VA POC, or an APPROVED one for the executor. */
    Optional<ListRow> open() {
        return jdbc.sql(OPEN).query(DisposalStore::list).optional();
    }

    Optional<ListRow> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query(DisposalStore::list).optional();
    }

    /** The newest lists, newest first. */
    List<ListRow> newest(int limit) {
        return jdbc.sql(NEWEST).param("limit", limit).query(DisposalStore::list).list();
    }

    List<DisposalItem> items(UUID listId) {
        return jdbc.sql(ITEMS).param("list", listId).query(DisposalStore::item).list();
    }

    UUID insert(LocalDate computedOn, Instant now, @Nullable Instant cutoff, String fingerprint, List<DisposalItem> items) {
        UUID id = jdbc.sql(INSERT_LIST).param("computedOn", computedOn).param("now", utc(now))
                .param("cutoff", cutoff == null ? null : utc(cutoff)).param("fingerprint", fingerprint)
                .query(UUID.class).single();
        for (DisposalItem item : items) {
            jdbc.sql(INSERT_ITEM).param("list", id).param("kind", item.kind().name()).param("target", item.targetId())
                    .param("system", item.systemId()).param("cycle", item.cycle()).param("reason", item.reason().name())
                    .param("entries", item.entries()).update();
        }
        return id;
    }

    void supersede(UUID id) {
        jdbc.sql(SUPERSEDE).param("id", id).update();
    }

    /** Approves a DRAFT list; the caller holds {@link #lock()} and has read it as DRAFT. */
    void approve(UUID id, UUID by, Instant now) {
        jdbc.sql(APPROVE).param("id", id).param("by", by).param("now", utc(now)).update();
    }

    /** The VA Submissions of a Cycle, the earliest started first. */
    List<Due> submissionsOf(int cycle) {
        return jdbc.sql(SUBMISSIONS_OF).param("cycle", cycle).query(DisposalStore::due).list();
    }

    /** The Scan Report files of a Cycle's VA Submissions, in the order they were received. */
    List<Due> filesOf(int cycle) {
        return jdbc.sql(FILES_OF).param("cycle", cycle).query(DisposalStore::due).list();
    }

    /** Whether anything of the Cycle is still kept. */
    boolean cycleHolds(int cycle) {
        return Boolean.TRUE.equals(jdbc.sql(CYCLE_HOLDS).param("cycle", cycle).query(Boolean.class).single());
    }

    /** The superseded files of Endorsed VA Submissions whose content is still held, in the order they were received. */
    List<Due> supersededDue() {
        return jdbc.sql(SUPERSEDED_DUE).query(DisposalStore::due).list();
    }

    /** The Cycles a System still has records in. */
    List<Integer> cyclesOfSystem(UUID systemId) {
        return jdbc.sql(CYCLES_OF_SYSTEM).param("system", systemId).query(Integer.class).list();
    }

    long auditBefore(Instant before) {
        return jdbc.sql(AUDIT_BEFORE).param("before", utc(before)).query(Long.class).single();
    }

    private static Due due(ResultSet rs, int row) throws SQLException {
        return new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3));
    }

    /** The exports whose rows are due. */
    List<UUID> expiredExports(Instant dayAgo) {
        return jdbc.sql(EXPIRED_EXPORTS).param("dayAgo", utc(dayAgo)).query(UUID.class).list();
    }

    /** The list waiting for the executor. */
    Optional<ListRow> approved() {
        return jdbc.sql(APPROVED).query(DisposalStore::list).optional();
    }

    /** The file-store objects an approved list's records hold. */
    List<Blob> blobs(UUID listId) {
        return jdbc.sql(BLOBS).param("list", listId)
                .query((rs, row) -> new Blob(FileStore.Zone.valueOf(rs.getString("zone")), rs.getString("key"))).list();
    }

    /**
     * Deletes what the approved list names, in foreign-key order, and marks it EXECUTED; the caller holds {@link #lock()}
     * and has read the list as APPROVED. Inside the caller's transaction, which the setting is local to.
     */
    Counts dispose(UUID listId, Instant now) {
        jdbc.sql(RUNNING).param("list", listId).query().singleRow();
        run(BEFORE_FILES, listId);
        int files = update(FILES, listId);
        run(BEFORE_SUBMISSIONS, listId);
        int submissions = update(SUBMISSIONS, listId);
        run(BEFORE_SYSTEMS, listId);
        int systems = update(SYSTEMS, listId);
        int exports = update(EXPORTS, listId);
        int audit = update(AUDIT, listId);
        int superseded = jdbc.sql(SUPERSEDED).param("list", listId).param("now", utc(now)).update();
        jdbc.sql(EXECUTED).param("list", listId).param("now", utc(now)).update();
        return new Counts(submissions, files, superseded, exports, systems, audit);
    }

    private void run(String[] statements, UUID listId) {
        for (String statement : statements) {
            update(statement, listId);
        }
    }

    private int update(String statement, UUID listId) {
        return jdbc.sql(statement).param("list", listId).update();
    }

    private static ListRow list(ResultSet rs, int row) throws SQLException {
        return new ListRow(rs.getObject("id", UUID.class), ListStatus.valueOf(rs.getString("status")),
                rs.getObject("computed_on", LocalDate.class), instant(rs, "created_at"), instant(rs, "cutoff"),
                rs.getString("fingerprint"), rs.getObject("approved_by", UUID.class), instant(rs, "approved_at"),
                instant(rs, "executed_at"));
    }

    private static DisposalItem item(ResultSet rs, int row) throws SQLException {
        return new DisposalItem(ItemKind.valueOf(rs.getString("kind")), rs.getObject("target_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getObject("cycle_year", Integer.class),
                DisposalReason.valueOf(rs.getString("reason")), rs.getObject("entries", Long.class));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
