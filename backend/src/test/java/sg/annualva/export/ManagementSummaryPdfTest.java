package sg.annualva.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Stories 81 and 86 (ticket 45): the same management summary as a PDF. Every page carries the classification in its
 * header and footer and as a diagonal, semi-transparent watermark, and the first line of the content states it; the PDF
 * holds no vulnerability detail, and it is asked for, downloaded and audited like the Excel export. The default clock is
 * the first day of Q3 of VA Cycle 2027.
 */
class ManagementSummaryPdfTest extends ExportSeam1 {

    static final String PDF_CYCLE = "{\"type\":\"MGMT_SUMMARY\",\"format\":\"PDF\"}";

    /** Asks for the PDF, runs the export job and downloads it; checks it is served as a PDF attachment. */
    private PdfRead madeAndDownloaded(ApiClient poc, String body, String fileName) {
        JsonNode queued = requested(poc, body);
        assertThat(queued.get("format").asString()).isEqualTo("PDF");
        exportJob();
        var download = poc.download(EXPORTS + "/" + queued.get("id").asString() + "/download");
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.header("Content-Type")).isEqualTo("application/pdf");
        assertThat(download.header("Content-Disposition")).isEqualTo("attachment; filename=\"" + fileName + "\"");
        return PdfRead.of(download.body());
    }

    private static void assertClassifiedOnEveryPage(PdfRead pdf, String label) {
        assertThat(pdf.pages()).isNotEmpty();
        for (int p = 0; p < pdf.pages().size(); p++) {
            PdfRead.Page page = pdf.pages().get(p);
            assertThat(page.header()).as("page %d header", p + 1).isEqualTo(label);
            List<String> lines = page.text().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
            assertThat(lines.get(1)).as("page %d first line of the content", p + 1).isEqualTo(label);
            assertThat(page.footer()).as("page %d footer", p + 1).contains(label)
                    .contains("Page " + (p + 1) + " of " + pdf.pages().size());
            assertThat(page.slanted()).as("page %d watermark", p + 1).isEqualTo(label);
            assertThat(page.translucent()).as("page %d watermark is semi-transparent", p + 1).isTrue();
            assertThat(page.illegible()).as("page %d text drawn over other text or off the page", p + 1).isEmpty();
        }
    }

    @Test
    void thePdfHoldsTheSameSummaryWithTheClassificationOnEveryPageAndNoVulnerabilityDetail() {
        registered(listed("SYS-P1", "Payroll", SMO_A, CM, "Q3"), listed("SYS-P2", "Portal", SMO_B, CM, "Q3"),
                listed("SYS-P3", "Next Quarter", SMO_A, CM, "Q4"));
        ApiClient smoA = signedInAs(SMO_A);
        String draft = started(smoA, "SYS-P1");
        enteredFindings(smoA, draft, 1);
        today("2027-08-16");
        arrange.overdueSweep();
        ApiClient poc = vaPoc();

        PdfRead pdf = madeAndDownloaded(poc, PDF_CYCLE, "management-summary-2027.pdf");

        assertClassifiedOnEveryPage(pdf, "Confidential");
        String text = pdf.text();
        assertThat(text).contains("Management summary, VA Cycle 2027").contains("As at 2027-08-16")
                .contains("Q3 2027-08-02 2027-10-31").contains("Q4 2027-11-01 2028-02-06")
                .contains("SYS-P1 Payroll Q3 " + SMO_A + " Draft Yes No 0")
                .contains("SYS-P2 Portal Q3 " + SMO_B + " Not Started Yes No 0")
                .contains("SYS-P3 Next Quarter Q4 " + SMO_A + " Not Started No No 0");
        assertThat(text).as("no vulnerability detail").doesNotContain("Entered 1").doesNotContain("CVE-");
    }

    @Test
    void aLongSummaryRunsOverPagesThatEachCarryTheClassificationAndTheSystemsHeader() {
        List<Listed> systems = new ArrayList<>();
        systems.add(listed("SYS-U00", "Café 系统 அ", SMO_A, CM, "Q3"));
        systems.add(listed("SYS-U01", "Grants management and disbursement platform for the community outreach "
                + "programmes of the agency, with its reporting services", SMO_B, CM, "Q3"));
        systems.add(listed("SYS-U02", "Grants-management-and-disbursement-platform-for-community-outreach", SMO_A, CM,
                "Q3"));
        for (int n = 3; n < 60; n++) {
            systems.add(listed("SYS-U%02d".formatted(n), "System " + n, n % 2 == 0 ? SMO_A : SMO_B, CM, "Q3"));
        }
        registered(systems, List.of());

        PdfRead pdf = madeAndDownloaded(vaPoc(), PDF_CYCLE, "management-summary-2027.pdf");

        assertThat(pdf.pages()).hasSizeGreaterThan(1);
        assertClassifiedOnEveryPage(pdf, "Confidential");
        for (PdfRead.Page page : pdf.pages().subList(1, pdf.pages().size())) {
            assertThat(page.text()).as("the Systems table's header row again").contains("System ID System Name");
        }
        String text = pdf.text();
        assertThat(text).as("a character Helvetica cannot draw is written as ?")
                .contains("SYS-U00 Café ?? ? Q3 " + SMO_A + " Not Started");
        assertThat(text).as("long names wrap within their column, a word wider than it broken inside")
                .contains("SYS-U01 Grants management").contains("SYS-U02 Grants-management-")
                .contains("SYS-U59 System 59 Q3");
    }

    @Test
    void someSystemsAreRestrictedOnEveryPage() {
        registered(listed("SYS-R1", "One", SMO_A, CM, "Q3"), listed("SYS-R2", "Two", SMO_A, CM, "Q3"));
        ApiClient poc = vaPoc();
        String one = "";
        for (JsonNode row : ok(poc, "/api/dashboards/cycle-matrix").get("systems")) {
            if (row.get("systemId").asString().equals("SYS-R1")) {
                one = row.get("id").asString();
            }
        }

        PdfRead pdf = madeAndDownloaded(poc, "{\"type\":\"MGMT_SUMMARY\",\"format\":\"PDF\",\"quarter\":3,"
                + "\"systems\":[\"" + one + "\"]}", "management-summary-2027-q3.pdf");

        assertClassifiedOnEveryPage(pdf, "Restricted / Sensitive-Normal");
        assertThat(pdf.text()).contains("Management summary, VA Cycle 2027, Q3").contains("SYS-R1")
                .doesNotContain("SYS-R2");
    }

    @Test
    void askingForAndDownloadingThePdfAreAuditedWithItsFormat() {
        registered(listed("SYS-A1", "Audited", SMO_A, CM, "Q3"));
        ApiClient poc = vaPoc();
        String id = requested(poc, PDF_CYCLE).get("id").asString();
        exportJob();
        assertThat(poc.download(EXPORTS + "/" + id + "/download").status()).isEqualTo(200);

        String actor = userId(poc);
        JsonNode asked = entries("EXPORT_REQUESTED", actor, id).getFirst();
        assertThat(asked.get("detail").get("format").asString()).isEqualTo("PDF");
        assertThat(asked.get("detail").get("classification").asString()).isEqualTo("CONFIDENTIAL");
        List<JsonNode> downloads = entries("EXPORT_DOWNLOADED", actor, id);
        assertThat(downloads).hasSize(1);
        assertThat(downloads.getFirst().get("outcome").asString()).isEqualTo("SUCCESS");
        assertThat(downloads.getFirst().get("detail").get("format").asString()).isEqualTo("PDF");
        assertThat(downloads.getFirst().toString()).doesNotContain("SYS-A1").doesNotContain("Audited");
    }
}
