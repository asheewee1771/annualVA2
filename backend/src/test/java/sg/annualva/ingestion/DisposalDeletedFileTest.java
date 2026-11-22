package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 60, acceptance criterion 3 (ADR 0061 item 2): a deleted report file is a soft delete, so its row and its content
 * stay, unreadable, until its VA Cycle is disposed of; the approved disposal of the Cycle then removes them with the rest
 * of the VA Submission's files, as the retention rules of ticket 49 say.
 */
class DisposalDeletedFileTest extends DisposalSeam1 {

    @Test
    void aDeletedReportFileIsKeptUntilItsCycleIsDisposedOfAndThenGoesWithIt() {
        Draft draft = draft();
        String file = parsedDc(draft, "mistake.json", dcReportAt(SCANNED));
        DeleteSeam1.fileDeleted(draft.smo(), draft.submissionId(), file);
        assertThat(fileStore.keys(FileStore.Zone.CLEAN)).as("its content is kept, unreadable").contains(file);
        assertThat(arrange.rows().get("report_file").asLong()).as("and its row").isEqualTo(1);

        today("2028-02-14");
        lifecycle();
        closed(vaPoc(), 2027);
        today("2031-02-15");
        retention();
        JsonNode list = listIn(vaPoc(), "DRAFT");
        assertThat(items(list)).as("the retention rules list it like any file of the Cycle")
                .contains("FILE " + file + " CYCLE_RETENTION_ENDED");
        approved(vaPoc(), list.get("id").asString());
        retention();

        assertThat(listIn(vaPoc(), "EXECUTED").get("id").asString()).isEqualTo(list.get("id").asString());
        assertThat(fileStore.keys(FileStore.Zone.CLEAN)).as("its content is disposed of").doesNotContain(file);
        JsonNode rows = arrange.rows();
        assertThat(rows.get("report_file").asLong()).as("and its row").isZero();
        assertThat(rows.get("report_file_stage").asLong()).isZero();
    }
}
