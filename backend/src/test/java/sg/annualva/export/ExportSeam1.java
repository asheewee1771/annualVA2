package sg.annualva.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import sg.annualva.dashboard.CyberManagerSeam1;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the export tests share (ticket 44, stories 81 and 86): a VA POC asks for an export, the export job (which a test
 * runs by hand, as scheduling is off) produces it, and the requester downloads it. A downloaded workbook is read back
 * cell by cell as text, the way a person would read it.
 */
abstract class ExportSeam1 extends CyberManagerSeam1 {

    static final String EXPORTS = "/api/exports";
    static final String WHOLE_CYCLE = "{\"type\":\"MGMT_SUMMARY\",\"format\":\"XLSX\"}";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** The text of every field of {@link #sentinelFinding}: no export may ever hold it. */
    static final String SENTINEL = "CVE-9999-SENTINEL";

    /** Enters a Finding by hand whose every text field is the sentinel; its id. */
    protected static String sentinelFinding(ApiClient smo, String submissionId) {
        var response = smo.postJson("/api/submissions/" + submissionId + "/findings/manual",
                "{\"reportType\":\"DEPENDENCY_CHECK\",\"title\":\"" + SENTINEL + "\",\"identifier\":\"" + SENTINEL
                        + "\",\"packageOrRule\":\"" + SENTINEL + " 1.0\",\"severity\":\"CRITICAL\"}");
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response).get("id").asString();
    }

    protected static void proposedAs(ApiClient smo, String findingId, String body) {
        var response = smo.postJson("/api/findings/" + findingId + "/proposals", body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }

    /** Asks for an export and expects it queued (202); the export as answered. */
    protected static JsonNode requested(ApiClient client, String body) {
        var response = client.postJson(EXPORTS, body);
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        return ApiClient.json(response);
    }

    /** One pass of the export job. */
    protected void exportJob() {
        arrange.exports();
    }

    /** The export as its requester reads it. */
    protected static JsonNode export(ApiClient client, String id) {
        return ok(client, EXPORTS + "/" + id);
    }

    /** Downloads a ready export and opens it as a workbook. */
    protected static XSSFWorkbook downloaded(ApiClient client, String id) {
        var download = client.download(EXPORTS + "/" + id + "/download");
        assertThat(download.status()).isEqualTo(200);
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(download.body()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A VA POC asks for the whole Cycle's summary, the job makes it, and the workbook is downloaded. */
    protected XSSFWorkbook summaryOfTheCycle(ApiClient poc) {
        String id = requested(poc, WHOLE_CYCLE).get("id").asString();
        exportJob();
        return downloaded(poc, id);
    }

    /** Every row of a sheet as the text of its cells, blank rows as empty lists. */
    protected static List<List<String>> rows(XSSFWorkbook workbook, String sheetName) {
        XSSFSheet sheet = workbook.getSheet(sheetName);
        assertThat(sheet).as("sheet " + sheetName).isNotNull();
        DataFormatter text = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            List<String> cells = new ArrayList<>();
            if (row != null) {
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : text.formatCellValue(cell));
                }
            }
            rows.add(cells);
        }
        return rows;
    }

    /** Everything a person could read in the workbook: every cell, header and footer of every sheet. */
    protected static String everything(XSSFWorkbook workbook) {
        StringBuilder all = new StringBuilder();
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            XSSFSheet sheet = workbook.getSheetAt(s);
            all.append(sheet.getSheetName()).append('\n')
                    .append(sheet.getHeader().getCenter()).append('\n')
                    .append(sheet.getFooter().getCenter()).append('\n');
            rows(workbook, sheet.getSheetName()).forEach(row -> all.append(String.join("|", row)).append('\n'));
        }
        return all.toString();
    }

    protected static String userId(ApiClient browser) {
        return ApiClient.json(browser.get("/api/session")).get("uuid").asString();
    }

    /** The entries of this action by this person, newest first; the log is the whole suite's. */
    protected List<JsonNode> entries(String action, String actor) {
        var response = otherVaPoc().get("/api/audit-entries?size=200&action=" + action + "&actor=" + actor);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        ApiClient.json(response).get("items").forEach(items::add);
        return items;
    }

    /** Those of them about this export. */
    protected List<JsonNode> entries(String action, String actor, String exportId) {
        return entries(action, actor).stream()
                .filter(entry -> exportId.equals(entry.get("detail").path("export_id").asString(""))).toList();
    }

    /** One refused download of this export, by this person, for this reason; ids and codes only. */
    protected void assertRefusedDownload(String actor, String exportId, String reason) {
        List<JsonNode> refused = entries("EXPORT_DOWNLOADED", actor, exportId).stream()
                .filter(entry -> entry.get("outcome").asString().equals("REFUSED"))
                .filter(entry -> entry.get("detail").get("reason").asString().equals(reason)).toList();
        assertThat(refused).hasSize(1);
        assertThat(refused.getFirst().toString()).doesNotContain("SYS-").doesNotContain("@ci.annualva.test");
    }

    /** The row of the sheet whose first cell is this text. */
    protected static List<String> rowStarting(List<List<String>> rows, String first) {
        return rows.stream().filter(row -> !row.isEmpty() && row.getFirst().equals(first)).findFirst()
                .orElseThrow(() -> new AssertionError("no row starting " + first + " in " + rows));
    }
}
