package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * Story 1: a VA POC downloads the fixed Excel template for the System Register. The file is read back with a
 * spreadsheet reader of its own, so what is asserted is what Excel would show a person, not what the writer meant.
 */
class RegisterTemplateTest extends Seam1Test {

    private static final String PATH = "/api/system-register/template";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOWNLOADED = "REGISTER_TEMPLATE_DOWNLOADED";
    private static final List<String> HEADERS = List.of(
            "System ID", "System Name", "SMO/Tech Lead email", "Cyber Manager email", "Quarter");

    @Test
    void aVaPocDownloadsAnExcelFileAsAnAttachment() {
        ApiClient poc = vaPoc();

        var response = poc.download(PATH);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("content-type")).isEqualTo(XLSX);
        assertThat(response.header("content-disposition")).isEqualTo("attachment; filename=\"system-register-template.xlsx\"");
        assertThat(response.header("cache-control")).contains("no-store");
        assertThat(response.header("x-content-type-options")).isEqualTo("nosniff");
        // an .xlsx is a zip archive
        assertThat(response.body()).startsWith((byte) 'P', (byte) 'K');
    }

    @Test
    void theSheetHasExactlyTheFiveColumnsInOrderAndNothingElse() throws IOException {
        try (Workbook workbook = open(vaPoc().download(PATH).body())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = workbook.getSheetAt(0);

            Row header = sheet.getRow(0);
            assertThat(texts(header)).isEqualTo(HEADERS);
            assertThat(header.getLastCellNum()).as("no sixth column").isEqualTo((short) 5);
            assertThat(sheet.getLastRowNum()).as("the header and one example row").isEqualTo(1);
        }
    }

    @Test
    void oneExampleRowShowsHowEachColumnIsFilledIn() throws IOException {
        try (Workbook workbook = open(vaPoc().download(PATH).body())) {
            List<String> example = texts(workbook.getSheetAt(0).getRow(1));

            assertThat(example).hasSize(5).allSatisfy(value -> assertThat(value).isNotBlank());
            assertThat(example.get(2)).contains("@");
            assertThat(example.get(3)).contains("@");
            assertThat(example.get(2)).isNotEqualTo(example.get(3));
            assertThat(example.get(4)).isIn("Q1", "Q2", "Q3", "Q4");
            // it must be recognisable as a sample, so nobody uploads it as a real System by mistake
            assertThat(example.get(1)).containsIgnoringCase("example");
        }
    }

    @Test
    void everyHeaderCarriesAHintAndTheFilledInColumnsAreChecked() throws IOException {
        try (Workbook workbook = open(vaPoc().download(PATH).body())) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int column = 0; column < HEADERS.size(); column++) {
                var comment = sheet.getCellComment(new CellAddress(0, column));
                assertThat(comment).as("hint on " + HEADERS.get(column)).isNotNull();
                assertThat(comment.getString().getString()).containsIgnoringCase("required");
            }
            assertThat(sheet.getCellComment(new CellAddress(0, 4)).getString().getString()).contains("Q1", "Q4");

            List<? extends DataValidation> checks = sheet.getDataValidations();
            DataValidation quarter = checkOn(checks, 4);
            assertThat(quarter.getValidationConstraint().getExplicitListValues()).containsExactly("Q1", "Q2", "Q3", "Q4");
            assertThat(quarter.getShowErrorBox()).isTrue();
            assertThat(checkOn(checks, 2).getShowErrorBox()).as("SMO/Tech Lead email").isTrue();
            assertThat(checkOn(checks, 3).getShowErrorBox()).as("Cyber Manager email").isTrue();
            assertThat(sheet.getPaneInformation()).as("the header row stays in view").isNotNull();
        }
    }

    @Test
    void everyColumnIsFormattedAsTextSoASystemIdKeepsItsLeadingZeros() throws IOException {
        try (Workbook workbook = open(vaPoc().download(PATH).body())) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int column = 0; column < HEADERS.size(); column++) {
                assertThat(sheet.getColumnStyle(column).getDataFormatString()).as(HEADERS.get(column)).isEqualTo("@");
            }
        }
    }

    @Test
    void theDownloadIsAuditedWithTheActorTheirRoleAndNoFileContent() {
        ApiClient poc = vaPoc();
        String userId = userId(poc);
        long before = downloads(poc, userId).get("total").asLong();

        assertThat(poc.download(PATH).status()).isEqualTo(200);

        JsonNode after = downloads(poc, userId);
        assertThat(after.get("total").asLong()).isEqualTo(before + 1);
        JsonNode entry = after.get("items").get(0);
        assertThat(entry.get("action").asString()).isEqualTo(DOWNLOADED);
        assertThat(entry.get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(entry.get("actorId").asString()).isEqualTo(userId);
        assertThat(entry.get("actorRole").asString()).isEqualTo("VA_POC");
        assertThat(entry.get("systemId").isNull()).isTrue();
        assertThat(entry.get("detail").size()).isZero();
        assertThat(entry.get("correlationId").asString()).isNotBlank();
    }

    @Test
    void anSmoACyberManagerAnAuditorAndSomeoneWithNoRoleAreForbiddenAndNothingIsDownloaded() {
        String system = arrange.system();
        arrange.grant("SMO", "template.smo@ci.annualva.test", system);
        arrange.grant("CYBER_MANAGER", "template.manager@ci.annualva.test", system);
        arrange.grant("AUDITOR", "template.auditor@ci.annualva.test", null, "GRANT", null);
        ApiClient poc = vaPoc();
        long before = downloads(poc, null).get("total").asLong();

        String[][] people = {
            {"af-b101", "template.smo@ci.annualva.test"},
            {"af-b102", "template.manager@ci.annualva.test"},
            {"af-b103", "template.auditor@ci.annualva.test"},
            {"af-b104", "template.norole@ci.annualva.test"},
        };
        for (String[] person : people) {
            ApiClient browser = anotherBrowser();
            SignIn.signedIn(browser, issuer, TestIdentity.of(person[0], person[1], "Template Reader"));

            var response = browser.download(PATH);

            assertThat(response.status()).as(person[1]).isEqualTo(403);
            assertThat(response.header("content-type")).startsWith("application/problem+json");
            assertThat(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("https://annualva/problems/forbidden");
            assertThat(response.header("content-disposition")).isNull();
        }

        assertThat(downloads(poc, null).get("total").asLong()).isEqualTo(before);
    }

    @Test
    void aRefusedRequestIsRecordedAsARefusalOfTheRequest() {
        arrange.grant("SMO", "template.refused@ci.annualva.test", arrange.system());
        ApiClient smo = anotherBrowser();
        SignIn.signedIn(smo, issuer, TestIdentity.of("af-b105", "template.refused@ci.annualva.test", "Template Refused"));
        ApiClient poc = vaPoc();
        String actor = userId(smo);

        assertThat(smo.download(PATH).status()).isEqualTo(403);

        var refusals = ApiClient.json(poc.get("/api/audit-entries?action=REQUEST_REFUSED&actor=" + actor));
        assertThat(refusals.get("total").asLong()).isEqualTo(1);
        assertThat(refusals.get("items").get(0).get("detail").get("status").asInt()).isEqualTo(403);
    }

    @Test
    void withoutASessionTheRequestIsUnauthenticated() {
        var response = anotherBrowser().download(PATH);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        assertThat(response.header("content-disposition")).isNull();
    }

    @Test
    void theTemplateIsReadOnlyThroughTheApi() {
        ApiClient poc = vaPoc().withCsrf();

        assertThat(poc.postJson(PATH, "{}").status()).isIn(404, 405);
    }

    private ApiClient vaPoc() {
        ApiClient browser = anotherBrowser();
        return SignIn.signedIn(browser, issuer, TestIdentity.of("af-5001", "poc.one@ci.annualva.test", "POC One"));
    }

    private static String userId(ApiClient browser) {
        return ApiClient.json(browser.get("/api/session")).get("uuid").asString();
    }

    /** The newest download entries, optionally of one person. */
    private static JsonNode downloads(ApiClient reader, String actor) {
        var response = reader.get("/api/audit-entries?size=200&action=" + DOWNLOADED
                + (actor == null ? "" : "&actor=" + actor));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    private static Workbook open(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static List<String> texts(Row row) {
        var formatter = new DataFormatter();
        var values = new ArrayList<String>();
        for (Cell cell : row) {
            values.add(formatter.formatCellValue(cell));
        }
        return values;
    }

    private static DataValidation checkOn(List<? extends DataValidation> checks, int column) {
        return checks.stream()
                .filter(check -> check.getRegions().getCellRangeAddress(0).getFirstColumn() == column)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no validation on column " + column));
    }
}
