package sg.annualva.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 86 (ticket 44, assumption A-1): every export carries its classification in the header and footer of each sheet
 * and in its first row. It is Confidential when it covers the complete set (every System of the Quarter or the Cycle it
 * is about), otherwise Restricted / Sensitive-Normal.
 */
class ExportClassificationTest extends ExportSeam1 {

    private static final String RESTRICTED = "Restricted / Sensitive-Normal";

    @BeforeEach
    void threeSystems() {
        registered(listed("SYS-K1", "One", SMO_A, CM, "Q3"), listed("SYS-K2", "Two", SMO_A, CM, "Q3"),
                listed("SYS-K3", "Three", SMO_B, CM, "Q4"));
    }

    /** The System's own id, as the Cycle matrix gives it. */
    private static String idOf(ApiClient poc, String systemId) {
        for (JsonNode row : ok(poc, "/api/dashboards/cycle-matrix").get("systems")) {
            if (row.get("systemId").asString().equals(systemId)) {
                return row.get("id").asString();
            }
        }
        throw new AssertionError("no " + systemId);
    }

    private XSSFWorkbook made(ApiClient poc, String body, String classification) {
        JsonNode queued = requested(poc, body);
        assertThat(queued.get("classification").asString()).isEqualTo(classification);
        exportJob();
        return downloaded(poc, queued.get("id").asString());
    }

    private static void assertClassified(XSSFWorkbook workbook, String label) {
        for (String name : List.of("Summary", "Systems")) {
            XSSFSheet sheet = workbook.getSheet(name);
            assertThat(sheet.getHeader().getCenter()).as(name + " header").isEqualTo(label);
            assertThat(sheet.getFooter().getCenter()).as(name + " footer").isEqualTo(label);
            assertThat(rows(workbook, name).getFirst()).as(name + " first row").containsExactly(label);
        }
    }

    @Test
    void theWholeCycleAndAWholeQuarterAreConfidential() {
        ApiClient poc = vaPoc();

        assertClassified(made(poc, WHOLE_CYCLE, "CONFIDENTIAL"), "Confidential");
        XSSFWorkbook quarter = made(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"cycle\":2027,\"quarter\":3}",
                "CONFIDENTIAL");

        assertClassified(quarter, "Confidential");
        assertThat(rows(quarter, "Summary").get(1)).containsExactly("Management summary, VA Cycle 2027, Q3");
        assertThat(rows(quarter, "Summary")).as("only Q3 is counted")
                .noneMatch(row -> !row.isEmpty() && row.getFirst().equals("Q4"));
        assertThat(everything(quarter)).contains("SYS-K1").contains("SYS-K2").doesNotContain("SYS-K3");
    }

    @Test
    void someSystemsAreRestrictedAndCountedAloneWhileEverySystemNamedOneByOneIsConfidential() {
        ApiClient poc = vaPoc();
        String one = idOf(poc, "SYS-K1");
        String two = idOf(poc, "SYS-K2");

        XSSFWorkbook some = made(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"quarter\":3,\"systems\":[\"" + one
                + "\"]}", "RESTRICTED");

        assertClassified(some, RESTRICTED);
        assertThat(rowStarting(rows(some, "Summary"), "Q3").get(3)).as("Not Started counts SYS-K1 alone").isEqualTo("1");
        assertThat(rows(some, "Systems")).hasSize(3);
        assertThat(everything(some)).contains("SYS-K1").doesNotContain("SYS-K2");

        XSSFWorkbook every = made(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\",\"quarter\":3,\"systems\":[\"" + one
                + "\",\"" + two + "\"]}", "CONFIDENTIAL");
        assertClassified(every, "Confidential");
    }
}
