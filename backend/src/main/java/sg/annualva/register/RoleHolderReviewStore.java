package sg.annualva.register;

import static sg.annualva.jdbc.Timestamps.requiredInstant;
import static sg.annualva.jdbc.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The role_holder_review table (V40, V43): the completed role-holder reviews. Constant SQL, named parameters (as-2). */
@Repository
class RoleHolderReviewStore {

    private static final String INSERT = "INSERT INTO role_holder_review (reviewed_at, reviewed_by, holders, grant_ids) "
            + "VALUES (:at, :by, :holders, CAST(:grants AS uuid[]))";

    private static final String LATEST = "SELECT reviewed_at, reviewed_by, holders, grant_ids FROM role_holder_review "
            + "ORDER BY reviewed_at DESC, id DESC LIMIT 1";

    private final JdbcClient jdbc;

    RoleHolderReviewStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Keeps a review of these grants; their number is what it listed. */
    CompletedReview insert(Instant at, UUID by, List<UUID> grantIds) {
        jdbc.sql(INSERT).param("at", utc(at)).param("by", by).param("holders", grantIds.size())
                .param("grants", grantIds.stream().map(UUID::toString).toArray(String[]::new)).update();
        return new CompletedReview(at, by, grantIds.size(), grantIds);
    }

    Optional<CompletedReview> latest() {
        return jdbc.sql(LATEST).query((rs, row) -> new CompletedReview(
                requiredInstant(rs, "reviewed_at"),
                rs.getObject("reviewed_by", UUID.class),
                rs.getInt("holders"),
                grantIds(rs))).optional();
    }

    /** The grants a review checked; none for a review recorded before V43. */
    private static List<UUID> grantIds(ResultSet rs) throws SQLException {
        return Arrays.asList((UUID[]) rs.getArray("grant_ids").getArray());
    }
}
