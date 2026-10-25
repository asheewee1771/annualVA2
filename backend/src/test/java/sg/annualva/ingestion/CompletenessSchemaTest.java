package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.audit.DetailKey;
import sg.annualva.harness.Seam1Test;
import sg.annualva.submission.SubmissionState;

/**
 * Ticket 50 as a gate on the schema the migrations build, beside the Seam 1 suites of the audit list and the state table:
 * the audit log's detail keys and the VA Submission's states are each a list in the code and a CHECK in the database
 * (ADR 0014, ADR 0048). A key or a state added to one and not the other fails only when it is first written, which may be
 * in production; this reads the CHECKs from the database and fails the build instead.
 */
class CompletenessSchemaTest extends Seam1Test {

    private static final String DEFINITION = "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
            + "WHERE c.conname = :name AND c.connamespace = to_regnamespace(current_schema())";
    private static final Pattern QUOTED = Pattern.compile("'([A-Za-z_]+)'");

    @Autowired
    private JdbcClient database;

    @Test
    void theAuditTableAcceptsExactlyTheDetailKeysTheCodeWrites() {
        Set<String> keys = new TreeSet<>(Arrays.stream(DetailKey.values())
                .map(key -> key.name().toLowerCase(Locale.ROOT)).toList());
        Set<String> accepted = quoted("audit_entry_detail_check");
        accepted.remove("object");

        assertThat(accepted).isEqualTo(keys);
    }

    @Test
    void theVaSubmissionAndNotificationTablesAcceptExactlyTheStatesOfTheStateTable() {
        Set<String> states = new TreeSet<>(Arrays.stream(SubmissionState.values()).map(Enum::name).toList());

        assertThat(quoted("va_submission_state_check")).isEqualTo(states);
        assertThat(quoted("notification_state_check")).isEqualTo(states);
    }

    /** Every quoted word of a CHECK's definition, as PostgreSQL prints it now. */
    private Set<String> quoted(String constraint) {
        String definition = database.sql(DEFINITION).param("name", constraint).query(String.class).single();
        Set<String> words = new TreeSet<>();
        Matcher matcher = QUOTED.matcher(definition);
        while (matcher.find()) {
            words.add(matcher.group(1));
        }
        return words;
    }
}
