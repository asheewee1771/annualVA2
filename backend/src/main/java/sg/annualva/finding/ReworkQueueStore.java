package sg.annualva.finding;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import sg.annualva.parser.Severity;

/**
 * The rework queue's reads (story 60): the Findings that need the SMO/Tech Lead ({@link FindingStore#NEEDS_ATTENTION}),
 * each in its stage, read with the columns of the Findings list. Constant SQL, named parameters (as-2).
 */
@Repository
class ReworkQueueStore {

    /** A Finding of the queue as the table holds it, with its stage. */
    record Queued(ReworkStage stage, FindingStore.Row row) {}

    /** The stage of a Finding that needs attention: Disputed, else new and grouped by severity. */
    private static final String STAGE =
            "(CASE WHEN f.review_state = '" + ReviewState.DISPUTED.name() + "' "
            + "THEN '" + ReworkStage.DISPUTED.name() + "' "
            + "WHEN f.severity IN ('" + Severity.CRITICAL.name() + "', '" + Severity.HIGH.name() + "') "
            + "THEN '" + ReworkStage.NEW_CRITICAL_HIGH.name() + "' "
            + "ELSE '" + ReworkStage.NEW_MEDIUM_LOW.name() + "' END)";

    /** The stages in queue order, the order of the enum, written once. */
    private static final String STAGE_ORDER = Arrays.stream(ReworkStage.values())
            .map(stage -> "'" + stage.name() + "'").collect(Collectors.joining(", ", "ARRAY[", "]"));

    private static final String IN_QUEUE = "WHERE f.submission_id = :submission AND " + FindingStore.NEEDS_ATTENTION + " ";

    /** Stage by stage, and within a stage the most severe first, as the Findings list orders them. */
    private static final String PAGE =
            "SELECT " + STAGE + " AS stage, " + FindingStore.FINDING_COLUMNS + FindingStore.FINDING_TABLES + IN_QUEUE
            + "ORDER BY array_position(" + STAGE_ORDER + ", " + STAGE + "), " + FindingStore.SEVERITY_FIRST + " "
            + "LIMIT :limit OFFSET :offset";

    private static final String COUNTS =
            "SELECT " + STAGE + " AS stage, count(*) AS findings FROM finding f " + IN_QUEUE + "GROUP BY 1";

    private final JdbcClient jdbc;

    ReworkQueueStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One page of the queue, in queue order. */
    List<Queued> page(UUID submissionId, int limit, long offset) {
        return jdbc.sql(PAGE).param("submission", submissionId).param("limit", limit).param("offset", offset)
                .query((rs, row) -> new Queued(ReworkStage.valueOf(rs.getString("stage")), FindingStore.row(rs)))
                .list();
    }

    /** How many Findings each stage holds, every stage present, in queue order. */
    Map<ReworkStage, Long> counts(UUID submissionId) {
        Map<ReworkStage, Long> counts = new EnumMap<>(ReworkStage.class);
        Arrays.stream(ReworkStage.values()).forEach(stage -> counts.put(stage, 0L));
        jdbc.sql(COUNTS).param("submission", submissionId)
                .query((rs, row) -> Map.entry(ReworkStage.valueOf(rs.getString("stage")), rs.getLong("findings")))
                .list()
                .forEach(count -> counts.put(count.getKey(), count.getValue()));
        return counts;
    }
}
