package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/** Story 87: what is due for disposal is listed on a draft list for a VA POC to review. */
class DisposalListTest extends DisposalSeam1 {

    @Test
    void anExportWhose24HoursAreUpIsListedOnADraftList() {
        String export = exportMade(vaPoc());
        clock.advance(Duration.ofHours(25));
        arrange.exports();

        retention();

        ApiClient poc = vaPoc();
        JsonNode lists = disposalLists(poc);
        assertThat(lists).hasSize(1);
        JsonNode draft = lists.get(0);
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("computedOn").asString()).isEqualTo("2027-08-03");
        assertThat(items(draft)).containsExactly("EXPORT " + export + " EXPORT_EXPIRED");
    }
}
