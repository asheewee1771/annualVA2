package sg.annualva.register;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The role_holder_review table (V40): the completed role-holder reviews. Constant SQL, named parameters (as-2). */
@Repository
class RoleHolderReviewStore {

    private static final String INSERT =
            "INSERT INTO role_holder_review (reviewed_at, reviewed_by, holders) VALUES (:at, :by, :holders)";

    private static final String LATEST = "SELECT reviewed_at, reviewed_by, holders FROM role_holder_review "
            + "ORDER BY reviewed_at DESC, id DESC LIMIT 1";

    private final JdbcClient jdbc;

    RoleHolderReviewStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    CompletedReview insert(Instant at, UUID by, int holders) {
        jdbc.sql(INSERT).param("at", at.atOffset(ZoneOffset.UTC)).param("by", by).param("holders", holders).update();
        return new CompletedReview(at, by, holders);
    }

    Optional<CompletedReview> latest() {
        return jdbc.sql(LATEST).query((rs, row) -> new CompletedReview(
                rs.getObject("reviewed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("reviewed_by", UUID.class),
                rs.getInt("holders"))).optional();
    }
}
