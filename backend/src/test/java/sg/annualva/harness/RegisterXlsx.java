package sg.annualva.harness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Builds the filled-in System Register template a VA POC would upload, with a workbook writer of its own. The header
 * texts are written out here, not read from the application, so a change to the template's headers breaks the upload
 * tests that depend on them (ADR 0016).
 */
public final class RegisterXlsx {

    public static final List<String> HEADERS = List.of(
            "System ID", "System Name", "SMO/Tech Lead email", "Cyber Manager email", "Quarter");

    private final List<String[]> rows = new ArrayList<>();
    private List<String> headers = HEADERS;
    private String sheetName = "System Register";
    private String formulaNameOnFirstRow;

    private RegisterXlsx() {}

    public static RegisterXlsx register() {
        return new RegisterXlsx();
    }

    /** One System row: System ID, System Name, SMO/Tech Lead email, Cyber Manager email, Quarter. */
    public RegisterXlsx row(String systemId, String name, String smo, String manager, String quarter) {
        rows.add(new String[] {systemId, name, smo, manager, quarter});
        return this;
    }

    public RegisterXlsx headers(String... headers) {
        this.headers = List.of(headers);
        return this;
    }

    public RegisterXlsx sheet(String name) {
        this.sheetName = name;
        return this;
    }

    /** The System Name of the first row is a formula that evaluates to this text, which is all the file then holds. */
    public RegisterXlsx nameByFormula(String formula) {
        this.formulaNameOnFirstRow = formula;
        return this;
    }

    public byte[] build() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet(sheetName);
            Row header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
            }
            for (int index = 0; index < rows.size(); index++) {
                Row row = sheet.createRow(index + 1);
                String[] values = rows.get(index);
                for (int column = 0; column < values.length; column++) {
                    if (values[column] != null) {
                        row.createCell(column).setCellValue(values[column]);
                    }
                }
                if (index == 0 && formulaNameOnFirstRow != null) {
                    Cell cell = row.createCell(1);
                    cell.setCellFormula(formulaNameOnFirstRow);
                    workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(cell);
                }
            }
            workbook.write(out);
            return rewriteParts(out.toByteArray(), xml -> xml.replace(NUL, "_x0000_").replace(LONE_SURROGATE, "_xD83D_"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Written into a cell as this text, it reaches the file as the XML escape for a NUL, which readers decode. */
    public static final String NUL = "@@NUL@@";
    /** The same for a lone high surrogate, which no well-formed UTF-8 text can hold. */
    public static final String LONE_SURROGATE = "@@SURROGATE@@";

    /** The workbook with a DOCTYPE declaring an external entity inserted into one of its XML parts. */
    public static byte[] withExternalEntity(byte[] workbook, String part) {
        String doctype = "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>";
        return rewritePart(workbook, part, xml -> xml.replaceFirst("(<\\?xml[^>]*\\?>)", "$1" + doctype));
    }

    /** The workbook with this many more archive parts, each a few bytes. */
    public static byte[] withExtraParts(byte[] workbook, int count) {
        try (var in = new ZipInputStream(new ByteArrayInputStream(workbook));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                zip.putNextEntry(new ZipEntry(entry.getName()));
                in.transferTo(zip);
                zip.closeEntry();
            }
            for (int i = 0; i < count; i++) {
                zip.putNextEntry(new ZipEntry("xl/extra/part" + i + ".txt"));
                zip.write(1);
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] rewritePart(byte[] workbook, String part, UnaryOperator<String> change) {
        return rewriteParts(workbook, part::equals, change);
    }

    private static byte[] rewriteParts(byte[] workbook, UnaryOperator<String> change) {
        return rewriteParts(workbook, name -> name.endsWith(".xml"), change);
    }

    private static byte[] rewriteParts(byte[] workbook, Predicate<String> which, UnaryOperator<String> change) {
        try (var in = new ZipInputStream(new ByteArrayInputStream(workbook));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                zip.putNextEntry(new ZipEntry(entry.getName()));
                byte[] content = in.readAllBytes();
                if (which.test(entry.getName())) {
                    content = change.apply(new String(content, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                }
                zip.write(content);
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A workbook with the given sheet of numbers only, for a file that is a real workbook but not a Register. */
    public static byte[] workbookWithSheets(String... names) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String name : names) {
                workbook.createSheet(name).createRow(0).createCell(0).setCellValue("x");
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
