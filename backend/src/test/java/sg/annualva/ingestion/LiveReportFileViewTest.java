package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.harness.Seam1Test;

/**
 * Ticket 68 as a gate on the schema the migrations build: the {@code report_file_live} view (V48, ADR 0061 item 8) is
 * {@code SELECT *} of {@code report_file}, and PostgreSQL fixes a view's columns when it is made. A migration that adds a
 * column to the table and does not recreate the view fails here, rather than in the one statement that reads the new
 * column through the view.
 */
class LiveReportFileViewTest extends Seam1Test {

    private static final String COLUMNS = "SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema = current_schema() AND table_name = :relation ORDER BY ordinal_position";

    @Autowired
    private JdbcClient database;

    @Test
    void theLiveViewHasEveryColumnOfTheTableInOrder() {
        List<String> table = columnsOf("report_file");

        assertThat(table).contains("id", "deleted_at");
        assertThat(columnsOf("report_file_live")).as("recreate report_file_live after changing report_file's columns")
                .containsExactlyElementsOf(table);
    }

    private List<String> columnsOf(String relation) {
        return database.sql(COLUMNS).param("relation", relation).query(String.class).list();
    }
}
