package sg.annualva.submission;

import static sg.annualva.jdbc.Timestamps.instant;
import static sg.annualva.jdbc.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The versions of the Declaration wording (V19, ticket 61): the current one, every one, and the next one published. A
 * version is only ever inserted; the table's trigger refuses anything else. Constant SQL, named parameters (as-2).
 */
@Repository
class DeclarationStore {

    /** The current version is the highest: a new wording is a new version, never a change to one. */
    private static final String CURRENT_VERSION = "(SELECT max(version) FROM declaration_text)";

    private static final String CURRENT =
            "SELECT version, text, placeholder FROM declaration_text WHERE version = " + CURRENT_VERSION;

    /**
     * A version as the VA POC's list shows it. The count is of the VA Submissions still kept that accepted it at a submit,
     * each once however often it was submitted with it.
     */
    private static final String LISTED =
            "SELECT t.version, t.text, t.placeholder, t.published_by, u.display_name AS published_by_name, "
            + "t.published_at, t.version = " + CURRENT_VERSION + " AS current, "
            + "(SELECT count(DISTINCT d.submission_id) FROM declaration d WHERE d.version = t.version) AS submissions "
            + "FROM declaration_text t LEFT JOIN app_user u ON u.id = t.published_by ";

    private static final String VERSIONS = LISTED + "ORDER BY t.version DESC";

    private static final String VERSION = LISTED + "WHERE t.version = :version";

    /** One publish at a time, so two never take the same next version number. */
    private static final String LOCK_PUBLISH = "SELECT pg_advisory_xact_lock(hashtext('annualva.declaration-publish'))";

    /** The next version after the highest, current from now; never a placeholder. */
    private static final String PUBLISH =
            "INSERT INTO declaration_text (version, text, placeholder, published_by, published_at) "
            + "SELECT max(version) + 1, :text, false, :by, :now FROM declaration_text RETURNING version";

    private final JdbcClient jdbc;

    DeclarationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The wording the SMO/Tech Lead is asked to accept now; V19 seeds a version, so there always is one. */
    Declarations.Text current() {
        return jdbc.sql(CURRENT)
                .query((rs, row) -> new Declarations.Text(rs.getInt("version"), rs.getString("text"),
                        rs.getBoolean("placeholder")))
                .single();
    }

    /** Every version, newest first. */
    List<Declarations.Version> versions() {
        return jdbc.sql(VERSIONS).query((rs, row) -> listed(rs)).list();
    }

    /** One version as the list shows it; it must exist. */
    Declarations.Version version(int version) {
        return jdbc.sql(VERSION).param("version", version).query((rs, row) -> listed(rs)).single();
    }

    /** Holds the publishing of a version until the transaction ends. */
    void holdPublishing() {
        jdbc.sql(LOCK_PUBLISH).query().singleRow();
    }

    /** Adds the wording as the next version, published by this person now; the caller holds the publishing. */
    int publish(String text, UUID by, Instant now) {
        return jdbc.sql(PUBLISH)
                .param("text", text)
                .param("by", by)
                .param("now", utc(now))
                .query(Integer.class)
                .single();
    }

    private static Declarations.Version listed(ResultSet rs) throws SQLException {
        return new Declarations.Version(rs.getInt("version"), rs.getString("text"), rs.getBoolean("placeholder"),
                rs.getObject("published_by", UUID.class), rs.getString("published_by_name"),
                instant(rs, "published_at"), rs.getBoolean("current"), rs.getInt("submissions"));
    }
}
