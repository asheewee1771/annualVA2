package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import tools.jackson.databind.JsonNode;

/**
 * The upload treats the file as untrusted (as-1, as-12): its type comes from its bytes, its size and the work it asks
 * for are capped, its headers must be the template's, and every refusal is a fixed problem that never repeats the file.
 */
class RegisterFileChecksTest extends RegisterSeam1 {

    private static final String PROBLEM = "https://annualva/problems/";

    @Test
    void theTypeIsDecidedFromTheBytesNotFromTheNameOrTheDeclaredType() {
        ApiClient poc = vaPoc();
        byte[] workbook = RegisterXlsx.register()
                .row("SYS-1", "One", "one@ci.annualva.test", "cm@ci.annualva.test", "Q1").build();

        var disguised = poc.postFile(UPLOAD, "file", "notes.txt", "text/plain", workbook);

        assertThat(disguised.status()).as("a real workbook is accepted whatever it is called").isEqualTo(201);
        for (byte[] notAWorkbook : new byte[][] {
            "System ID,System Name\nSYS-1,One\n".getBytes(StandardCharsets.UTF_8),
            new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0},
            new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0},
            "<html><body>CVE-9999-SENTINEL</body></html>".getBytes(StandardCharsets.UTF_8),
            zip("notes.txt", "hello"),
        }) {
            var response = poc.postFile(UPLOAD, "file", "register.xlsx", XLSX, notAWorkbook);

            assertThat(response.status()).isEqualTo(415);
            assertThat(problemType(response)).isEqualTo(PROBLEM + "unsupported-file-type");
            assertThat(response.body()).doesNotContain("SENTINEL").doesNotContain("SYS-1").doesNotContain("register.xlsx");
        }
    }

    @Test
    void aMacroEnabledWorkbookIsNotAccepted() {
        byte[] workbook = RegisterXlsx.register()
                .row("SYS-1", "One", "one@ci.annualva.test", "cm@ci.annualva.test", "Q1").build();

        var response = upload(vaPoc(), withExtraPart(workbook, "xl/vbaProject.bin"));

        assertThat(response.status()).isEqualTo(415);
        assertThat(problemType(response)).isEqualTo(PROBLEM + "unsupported-file-type");
    }

    @Test
    void anArchiveThatUnpacksToFarMoreThanItsSizeIsRefusedBeforeItIsOpened() {
        // thirty megabytes of zeros compress to a few kilobytes: well under 2 MB, far over what a Register can be
        byte[] bomb = withExtraPart(RegisterXlsx.register()
                .row("SYS-1", "One", "one@ci.annualva.test", "cm@ci.annualva.test", "Q1").build(),
                "xl/media/padding.bin", 30 * 1024 * 1024);
        assertThat(bomb.length).isLessThan(RegisterSheetReader.MAX_BYTES);

        var response = upload(vaPoc(), bomb);

        assertThat(response.status()).isEqualTo(422);
        assertThat(problemType(response)).isEqualTo(PROBLEM + "register-file-unreadable");
    }

    @Test
    void aBrokenArchiveIsUnreadableAndNeverQuotedBack() {
        byte[] broken = new byte[200];
        broken[0] = 'P';
        broken[1] = 'K';
        broken[2] = 3;
        broken[3] = 4;
        java.util.Arrays.fill(broken, 4, broken.length, (byte) 'x');

        var response = upload(vaPoc(), broken);

        assertThat(response.status()).isIn(415, 422);
        assertThat(response.body()).doesNotContain("xxxx");
    }

    @Test
    void aFileOverTwoMegabytesIsTooLargeAndAnEmptyFileIsEmpty() {
        ApiClient poc = vaPoc();
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        big[0] = 'P';
        big[1] = 'K';

        var tooLarge = upload(poc, big);
        var atTheLimit = upload(poc, new byte[2 * 1024 * 1024]);
        var empty = upload(poc, new byte[0]);

        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(problemType(tooLarge)).isEqualTo(PROBLEM + "file-too-large");
        assertThat(atTheLimit.status()).as("exactly 2 MB is within the limit and is then judged by its bytes").isEqualTo(415);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(problemType(empty)).isEqualTo(PROBLEM + "file-empty");
    }

    @Test
    void theHeadersMustBeTheTemplatesAsWrittenInOrderAndNothingAfterThem() {
        ApiClient poc = vaPoc();
        String[][] wrong = {
            {"System Id", "System Name", "SMO/Tech Lead email", "Cyber Manager email", "Quarter"},
            {"System ID", "Name", "SMO/Tech Lead email", "Cyber Manager email", "Quarter"},
            {"System Name", "System ID", "SMO/Tech Lead email", "Cyber Manager email", "Quarter"},
            {"System ID", "System Name", "SMO/Tech Lead email", "Cyber Manager email"},
            {"System ID", "System Name", "SMO/Tech Lead email", "Cyber Manager email", "Quarter", "Notes"},
        };
        String[] columns = {"A", "B", "A", "E", "F"};

        for (int i = 0; i < wrong.length; i++) {
            var response = upload(poc, RegisterXlsx.register().headers(wrong[i])
                    .row("SYS-1", "One", "one@ci.annualva.test", "cm@ci.annualva.test", "Q1").build());

            assertThat(response.status()).as("variant " + i).isEqualTo(422);
            assertThat(problemType(response)).isEqualTo(PROBLEM + "register-header-mismatch");
            assertThat(ApiClient.json(response).get("detail").asString()).contains("Column " + columns[i]);
        }
    }

    @Test
    void theSheetIsTheOneNamedSystemRegisterOrTheOnlyOne() {
        ApiClient poc = vaPoc();
        var renamed = upload(poc, RegisterXlsx.register().sheet("Sheet1")
                .row("SYS-1", "One", "one@ci.annualva.test", "cm@ci.annualva.test", "Q1").build());
        var none = upload(poc, RegisterXlsx.workbookWithSheets("Notes", "Other"));

        assertThat(renamed.status()).as("the only sheet is read whatever it is called").isEqualTo(201);
        assertThat(none.status()).isEqualTo(422);
        assertThat(problemType(none)).isEqualTo(PROBLEM + "register-sheet-missing");
    }

    @Test
    void aSheetWithNoRowsIsRefusedBecauseItWouldRetireEverySystem() {
        arrange.registered("SYS-SAFE", "Safe", "safe.smo@ci.annualva.test", "safe.cm@ci.annualva.test", 1, false);
        ApiClient poc = vaPoc();

        var headersOnly = upload(poc, RegisterXlsx.register().build());
        var blankRows = upload(poc, RegisterXlsx.register().row(null, null, null, null, null).build());

        assertThat(headersOnly.status()).isEqualTo(422);
        assertThat(problemType(headersOnly)).isEqualTo(PROBLEM + "register-no-rows");
        assertThat(blankRows.status()).isEqualTo(422);
    }

    @Test
    void moreThanAThousandSystemRowsAreRefusedAndAThousandAreAccepted() {
        ApiClient poc = vaPoc();
        RegisterXlsx thousand = RegisterXlsx.register();
        for (int i = 1; i <= 1000; i++) {
            thousand.row("SYS-" + i, "System " + i, "smo" + i + "@ci.annualva.test", "cm@ci.annualva.test", "Q" + (1 + i % 4));
        }
        RegisterXlsx tooMany = RegisterXlsx.register();
        for (int i = 1; i <= 1001; i++) {
            tooMany.row("SYS-" + i, "System " + i, "smo" + i + "@ci.annualva.test", "cm@ci.annualva.test", "Q1");
        }

        var accepted = upload(poc, thousand.build());
        var refused = upload(poc, tooMany.build());

        assertThat(accepted.status()).isEqualTo(201);
        JsonNode preview = ApiClient.json(accepted);
        assertThat(preview.get("rows")).hasSize(1000);
        assertThat(preview.get("counts").get("add").asInt()).isEqualTo(1000);
        assertThat(ApiClient.json(poc.get(PREVIEWS + preview.get("id").asString())).get("rows")).hasSize(1000);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(problemType(refused)).isEqualTo(PROBLEM + "register-too-many-rows");
    }

    @Test
    void aRequestWithNoFilePartOrNotMultipartIsRefusedWithoutRepeatingAnything() {
        ApiClient poc = vaPoc();

        var noFile = poc.postFile(UPLOAD, "document", "register.xlsx", XLSX, new byte[] {1, 2, 3});
        var json = poc.postJson(UPLOAD, "{\"file\":\"CVE-9999-SENTINEL\"}");

        assertThat(noFile.status()).isEqualTo(400);
        assertThat(json.status()).isEqualTo(415);
        assertThat(noFile.body() + json.body()).doesNotContain("SENTINEL").doesNotContain("document");
    }

    @Test
    void aWorkbookWithADoctypeOrAnExternalEntityIsUnreadableAndNothingIsEchoed() {
        ApiClient poc = vaPoc();
        byte[] workbook = RegisterXlsx.register()
                .row("SYS-1", "One", "one@ci.annualva.test", "cm@ci.annualva.test", "Q1").build();

        for (String part : new String[] {"xl/worksheets/sheet1.xml", "xl/workbook.xml", "xl/sharedStrings.xml"}) {
            var response = upload(poc, RegisterXlsx.withExternalEntity(workbook, part));

            assertThat(response.status()).as(part).isEqualTo(422);
            assertThat(problemType(response)).isEqualTo(PROBLEM + "register-file-unreadable");
            assertThat(response.body()).doesNotContain("passwd").doesNotContain("xxe").doesNotContain("DOCTYPE");
        }
    }

    @Test
    void anArchiveOfMoreThanTwoHundredPartsIsRefusedWithoutBeingOpened() {
        ApiClient poc = vaPoc();
        byte[] workbook = RegisterXlsx.register()
                .row("SYS-1", "One", "one@ci.annualva.test", "cm@ci.annualva.test", "Q1").build();
        int existing = (int) countParts(workbook);

        var overTheCap = upload(poc, RegisterXlsx.withExtraParts(workbook, 201 - existing));

        assertThat(overTheCap.status()).isEqualTo(422);
        assertThat(problemType(overTheCap)).isEqualTo(PROBLEM + "register-file-unreadable");
    }

    private static long countParts(byte[] workbook) {
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(workbook))) {
            long parts = 0;
            while (in.getNextEntry() != null) {
                parts++;
            }
            return parts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] zip(String name, String content) {
        try (var out = new ByteArrayOutputStream(); var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] withExtraPart(byte[] workbook, String name) {
        return withExtraPart(workbook, name, 16);
    }

    /** The same workbook with one more archive part of {@code size} zero bytes. */
    private static byte[] withExtraPart(byte[] workbook, String name, int size) {
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(workbook));
                var out = new ByteArrayOutputStream();
                var zip = new ZipOutputStream(out)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                zip.putNextEntry(new ZipEntry(entry.getName()));
                in.transferTo(zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(name));
            byte[] zeros = new byte[64 * 1024];
            for (int written = 0; written < size; written += zeros.length) {
                zip.write(zeros, 0, Math.min(zeros.length, size - written));
            }
            zip.closeEntry();
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
