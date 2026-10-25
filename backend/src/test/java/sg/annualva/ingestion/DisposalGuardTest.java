package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one deliberate path through the append-only tables (V36, ADR 0050), proved on the application's own database
 * connection, as the audit log's append-only test is: whatever the application's code or a bug in it tried, a delete goes
 * through only inside a transaction that names a list a VA POC has approved, an update or a truncate never, the
 * Declaration wordings never, and an audit entry only before the approved list's cutoff. Every other removal is a soft
 * delete.
 */
class DisposalGuardTest extends DisposalSeam1 {

    /** Every table guarded by an append-only trigger, read from the schema so a new one is covered by default. */
    private List<String> appendOnlyTables() {
        List<String> tables = application.sql("SELECT DISTINCT event_object_table FROM information_schema.triggers "
                + "WHERE trigger_name LIKE '%append_only' ORDER BY 1").query(String.class).list();
        assertThat(tables).contains("audit_entry", "finding_status_proposal", "bulk_batch", "declaration_text");
        return tables;
    }

    @Autowired
    private JdbcClient application;

    @Autowired
    private TransactionTemplate transaction;

    /** A DRAFT list holding one expired export; its id. */
    private String draftList() {
        exportMade(vaPoc());
        clock.advance(Duration.ofHours(25));
        arrange.exports();
        retention();
        return listIn(vaPoc(), "DRAFT").get("id").asString();
    }

    /** Runs the statement in a transaction that names this list as the disposal it executes. */
    private void asDisposal(String listId, String statement) {
        transaction.executeWithoutResult(status -> {
            application.sql("SELECT set_config('annualva.disposal_list', :list, true)").param("list", listId).query()
                    .singleRow();
            application.sql(statement).update();
        });
    }

    @Test
    void namingAListThatNobodyApprovedOpensNothing() {
        String draft = draftList();

        for (String table : appendOnlyTables()) {
            assertThatThrownBy(() -> asDisposal(draft, "DELETE FROM " + table + " WHERE false"))
                    .as(table).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> asDisposal(UUID.randomUUID().toString(), "DELETE FROM " + table + " WHERE false"))
                    .as(table).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> asDisposal("not a list", "DELETE FROM " + table + " WHERE false"))
                    .as(table).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        }
    }

    @Test
    void anApprovedListOpensDeletesOnlyAndKeepsTheWordingsAndTheAuditEntriesFromItsCutoff() {
        String list = draftList();
        approved(vaPoc(), list);
        String newestEntry = application.sql("SELECT id::text FROM audit_entry ORDER BY seq DESC LIMIT 1")
                .query(String.class).single();

        assertThatCode(() -> asDisposal(list, "DELETE FROM bulk_batch WHERE false")).doesNotThrowAnyException();
        assertThatThrownBy(() -> asDisposal(list, "UPDATE bulk_batch SET proposed_at = proposed_at"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> asDisposal(list, "TRUNCATE submission_return_item"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> asDisposal(list, "DELETE FROM declaration_text WHERE false"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> asDisposal(list, "DELETE FROM audit_entry WHERE id = '" + newestEntry + "'"))
                .as("this list disposes of no Cycle, so it has no audit range")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("outside the approved disposal range");
        assertThatThrownBy(() -> application.sql("DELETE FROM bulk_batch WHERE false").update())
                .as("outside the executor's transaction the table is append-only as ever")
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }
}
