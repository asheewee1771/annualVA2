package sg.annualva.retention;

import static sg.annualva.jdbc.Timestamps.instant;
import static sg.annualva.jdbc.Timestamps.requiredInstant;
import static sg.annualva.jdbc.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.audit.AuditAction;
import sg.annualva.boundary.FileStore;

/**
 * The disposal lists (V36) and what they are computed from. Constant SQL, named parameters (as-2). Every move of a list
 * is guarded by the status it moves from, so two passes of the job, or two VA POCs, change it once.
 */
@Repository
class DisposalStore {

    /** A list as stored. */
    record ListRow(
            UUID id, ListStatus status, LocalDate computedOn, Instant createdAt, @Nullable Instant cutoff,
            @Nullable UUID approvedBy, @Nullable Instant approvedAt, @Nullable Instant executedAt) {}

    /** A record due, with the System it is about and its VA Submission's Cycle. */
    record DueRow(UUID id, UUID systemId, int cycle) {}

    private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtext('annualva.disposal'))";

    private static final String LIST_COLUMNS = "SELECT id, status, computed_on, created_at, cutoff, approved_by, "
            + "approved_at, executed_at FROM disposal_list ";

    private static final String OPEN = LIST_COLUMNS + "WHERE status IN ('DRAFT', 'APPROVED')";

    /** A superseded list was never approved and is shown nowhere; the newest of the others. */
    private static final String NEWEST = LIST_COLUMNS + "WHERE status <> 'SUPERSEDED' ORDER BY seq DESC LIMIT :limit";

    private static final String FIND = LIST_COLUMNS + "WHERE id = :id";

    private static final String APPROVED = LIST_COLUMNS + "WHERE status = 'APPROVED'";

    private static final String ITEMS = "SELECT kind, target_id, system_id, cycle_year, reason, entries FROM disposal_item "
            + "WHERE list_id = :list ORDER BY seq";

    private static final String INSERT_LIST = "INSERT INTO disposal_list (status, computed_on, created_at, cutoff) "
            + "VALUES ('DRAFT', :computedOn, :now, :cutoff) RETURNING id";

    private static final String INSERT_ITEM = "INSERT INTO disposal_item (list_id, kind, target_id, system_id, "
            + "cycle_year, reason, entries) VALUES (:list, :kind, :target, :system, :cycle, :reason, :entries)";

    private static final String SUPERSEDE = "UPDATE disposal_list SET status = 'SUPERSEDED' WHERE id = :id "
            + "AND status = 'DRAFT'";

    private static final String APPROVE = "UPDATE disposal_list SET status = 'APPROVED', approved_by = :by, "
            + "approved_at = :now WHERE id = :id AND status = 'DRAFT'";

    // ---- what is due ----

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

    /** The disposals' own entries are kept whatever the cutoff: they are the record that records were disposed of. */
    private static final String KEPT_ACTIONS = Stream.of(AuditAction.DISPOSAL_LISTED,
            AuditAction.DISPOSAL_APPROVED, AuditAction.DISPOSAL_EXECUTED).map(action -> "'" + action.name() + "'")
            .collect(Collectors.joining(", ", "(", ")"));

    private static final String AUDIT_BEFORE = "SELECT count(*) FROM audit_entry WHERE occurred_at < :before "
            + "AND action NOT IN " + KEPT_ACTIONS;

    /** An export whose 24 hours are up: the export job has removed its file. */
    private static final String EXPIRED_EXPORTS =
            "SELECT id FROM export_request WHERE status = 'EXPIRED' ORDER BY requested_at, id";

    // ---- executing an approved list: every statement reads what to remove from the list's own items ----

    /** A file-store object to remove before the records: its zone and key. */
    record Blob(FileStore.Zone zone, String key) {}

    /** What one execution removed: rows by what they were, and superseded files' content. */
    record Counts(Map<Counted, Integer> rows, int supersededFiles) {

        int of(Counted counted) {
            return rows.getOrDefault(counted, 0);
        }
    }

    /** The statements whose rows the tombstone counts. */
    enum Counted {
        SUBMISSIONS,
        FILES,
        SYSTEMS,
        EXPORTS,
        AUDIT_ENTRIES
    }

    /** One statement of the disposal, and what its rows count as on the tombstone (null for none). */
    record Step(String sql, @Nullable Counted counted) {}

    private static final String LISTED_SUBMISSIONS =
            "(SELECT target_id FROM disposal_item WHERE list_id = :list AND kind = 'SUBMISSION')";
    private static final String LISTED_SUPERSEDED_FILES =
            "(SELECT target_id FROM disposal_item WHERE list_id = :list AND kind = 'SUPERSEDED_FILE')";
    private static final String LISTED_EXPORTS =
            "(SELECT target_id FROM disposal_item WHERE list_id = :list AND kind = 'EXPORT')";
    /** A listed System that is still retired: a Register apply since the listing may have brought it back. */
    private static final String LISTED_RETIRED_SYSTEMS = "(SELECT i.target_id FROM disposal_item i "
            + "JOIN va_system v ON v.id = i.target_id WHERE i.list_id = :list AND i.kind = 'SYSTEM' "
            + "AND v.retired_at IS NOT NULL)";
    private static final String LISTED_CYCLES =
            "(SELECT cycle_year FROM disposal_item WHERE list_id = :list AND kind = 'CYCLE')";
    private static final String CUTOFF = "(SELECT cutoff FROM disposal_list WHERE id = :list)";
    private static final String LISTED_FINDINGS = "(SELECT id FROM finding WHERE submission_id IN " + LISTED_SUBMISSIONS
            + ")";
    /** The notifications about a disposed VA Submission or System, and those about no VA Submission before the cutoff. */
    private static final String LISTED_NOTIFICATIONS = "(SELECT id FROM notification WHERE submission_id IN "
            + LISTED_SUBMISSIONS + " OR system_id IN " + LISTED_RETIRED_SYSTEMS
            + " OR (submission_id IS NULL AND created_at < " + CUTOFF + "))";

    private static final String BLOBS = "SELECT 'CLEAN' AS zone, f.clean_key AS key FROM report_file f "
            + "WHERE (f.submission_id IN " + LISTED_SUBMISSIONS + " OR f.id IN " + LISTED_SUPERSEDED_FILES + ") "
            + "AND f.clean_key IS NOT NULL AND f.content_disposed_at IS NULL "
            + "UNION ALL SELECT 'QUARANTINE', f.quarantine_key FROM report_file f WHERE f.submission_id IN "
            + LISTED_SUBMISSIONS + " AND f.quarantine_key IS NOT NULL";

    private static final String RUNNING = "SELECT set_config('annualva.disposal_list', CAST(:list AS text), true)";

    /**
     * The disposal, in foreign-key order (ADR 0050): what names a record goes before it, and a rescan and the file it
     * replaced go in one statement. The proposals, batches, reviews, returns, Declarations and audit entries are
     * append-only; V36 lets these deletes through only while the list named by {@link #RUNNING} is APPROVED. A schema test
     * checks that every table naming a table deleted from here is deleted from here too.
     */
    static final List<Step> STEPS = List.of(
            new Step("DELETE FROM mail_outbox WHERE notification_id IN " + LISTED_NOTIFICATIONS, null),
            new Step("DELETE FROM notification WHERE id IN " + LISTED_NOTIFICATIONS, null),
            new Step("DELETE FROM submission_return_item WHERE return_id IN (SELECT id FROM submission_return "
                    + "WHERE submission_id IN " + LISTED_SUBMISSIONS + ")", null),
            new Step("DELETE FROM submission_return WHERE submission_id IN " + LISTED_SUBMISSIONS, null),
            new Step("DELETE FROM finding_review WHERE finding_id IN " + LISTED_FINDINGS, null),
            new Step("DELETE FROM batch_review WHERE batch_id IN (SELECT id FROM bulk_batch WHERE submission_id IN "
                    + LISTED_SUBMISSIONS + ")", null),
            new Step("DELETE FROM report_review WHERE submission_id IN " + LISTED_SUBMISSIONS, null),
            new Step("UPDATE finding SET current_proposal_id = NULL, review_state = NULL WHERE submission_id IN "
                    + LISTED_SUBMISSIONS + " AND current_proposal_id IS NOT NULL", null),
            new Step("DELETE FROM finding_status_proposal WHERE finding_id IN " + LISTED_FINDINGS, null),
            new Step("DELETE FROM bulk_batch WHERE submission_id IN " + LISTED_SUBMISSIONS, null),
            new Step("DELETE FROM finding_occurrence WHERE finding_id IN " + LISTED_FINDINGS, null),
            new Step("DELETE FROM finding WHERE submission_id IN " + LISTED_SUBMISSIONS, null),
            new Step("DELETE FROM report_file_stage WHERE file_id IN (SELECT id FROM report_file WHERE submission_id IN "
                    + LISTED_SUBMISSIONS + ")", null),
            new Step("DELETE FROM exemption WHERE submission_id IN " + LISTED_SUBMISSIONS, null),
            new Step("DELETE FROM report_file WHERE submission_id IN " + LISTED_SUBMISSIONS, Counted.FILES),
            new Step("DELETE FROM report_slot WHERE submission_id IN " + LISTED_SUBMISSIONS, null),
            new Step("DELETE FROM declaration WHERE submission_id IN " + LISTED_SUBMISSIONS, null),
            new Step("DELETE FROM submission_participation WHERE submission_id IN " + LISTED_SUBMISSIONS, null),
            new Step("DELETE FROM va_submission WHERE id IN " + LISTED_SUBMISSIONS, Counted.SUBMISSIONS),
            new Step("DELETE FROM not_started_overdue WHERE cycle_year IN " + LISTED_CYCLES + " OR system_id IN "
                    + LISTED_RETIRED_SYSTEMS, null),
            new Step("DELETE FROM cycle_register_system WHERE cycle_year IN " + LISTED_CYCLES + " OR system_id IN "
                    + LISTED_RETIRED_SYSTEMS, null),
            new Step("DELETE FROM cycle_register WHERE cycle_year IN " + LISTED_CYCLES, null),
            new Step("DELETE FROM register_upload WHERE created_at < " + CUTOFF, null),
            new Step("DELETE FROM role_grant WHERE system_id IN " + LISTED_RETIRED_SYSTEMS, null),
            new Step("DELETE FROM va_system WHERE id IN " + LISTED_RETIRED_SYSTEMS, Counted.SYSTEMS),
            new Step("DELETE FROM export_request WHERE id IN " + LISTED_EXPORTS, Counted.EXPORTS),
            new Step("DELETE FROM audit_entry WHERE occurred_at < " + CUTOFF + " AND action NOT IN " + KEPT_ACTIONS,
                    Counted.AUDIT_ENTRIES));

    private static final String SUPERSEDED = "UPDATE report_file SET content_disposed_at = :now WHERE id IN "
            + LISTED_SUPERSEDED_FILES + " AND content_disposed_at IS NULL";

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

    UUID insert(LocalDate computedOn, Instant now, @Nullable Instant cutoff, List<DisposalItem> items) {
        UUID id = jdbc.sql(INSERT_LIST).param("computedOn", computedOn).param("now", utc(now))
                .param("cutoff", cutoff == null ? null : utc(cutoff)).query(UUID.class).single();
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
    List<DueRow> submissionsOf(int cycle) {
        return jdbc.sql(SUBMISSIONS_OF).param("cycle", cycle).query(DisposalStore::due).list();
    }

    /** The Scan Report files of a Cycle's VA Submissions, in the order they were received. */
    List<DueRow> filesOf(int cycle) {
        return jdbc.sql(FILES_OF).param("cycle", cycle).query(DisposalStore::due).list();
    }

    /** Whether anything of the Cycle is still kept. */
    boolean cycleHolds(int cycle) {
        return Boolean.TRUE.equals(jdbc.sql(CYCLE_HOLDS).param("cycle", cycle).query(Boolean.class).single());
    }

    /** The superseded files of Endorsed VA Submissions whose content is still held, in the order they were received. */
    List<DueRow> supersededDue() {
        return jdbc.sql(SUPERSEDED_DUE).query(DisposalStore::due).list();
    }

    /** The Cycles a System still has records in. */
    List<Integer> cyclesOfSystem(UUID systemId) {
        return jdbc.sql(CYCLES_OF_SYSTEM).param("system", systemId).query(Integer.class).list();
    }

    /** How many audit entries the cutoff would dispose of. */
    long auditBefore(Instant before) {
        return jdbc.sql(AUDIT_BEFORE).param("before", utc(before)).query(Long.class).single();
    }

    /** The exports whose rows are due. */
    List<UUID> expiredExports() {
        return jdbc.sql(EXPIRED_EXPORTS).query(UUID.class).list();
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
        Map<Counted, Integer> rows = new EnumMap<>(Counted.class);
        for (Step step : STEPS) {
            int removed = jdbc.sql(step.sql()).param("list", listId).update();
            if (step.counted() != null) {
                rows.merge(step.counted(), removed, Integer::sum);
            }
        }
        int superseded = jdbc.sql(SUPERSEDED).param("list", listId).param("now", utc(now)).update();
        jdbc.sql(EXECUTED).param("list", listId).param("now", utc(now)).update();
        return new Counts(rows, superseded);
    }

    private static DueRow due(ResultSet rs, int row) throws SQLException {
        return new DueRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3));
    }

    private static ListRow list(ResultSet rs, int row) throws SQLException {
        return new ListRow(rs.getObject("id", UUID.class), ListStatus.valueOf(rs.getString("status")),
                rs.getObject("computed_on", LocalDate.class), requiredInstant(rs, "created_at"),
                instant(rs, "cutoff"), rs.getObject("approved_by", UUID.class), instant(rs, "approved_at"),
                instant(rs, "executed_at"));
    }

    private static DisposalItem item(ResultSet rs, int row) throws SQLException {
        return new DisposalItem(ItemKind.valueOf(rs.getString("kind")), rs.getObject("target_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getObject("cycle_year", Integer.class),
                DisposalReason.valueOf(rs.getString("reason")), rs.getObject("entries", Long.class));
    }

}
