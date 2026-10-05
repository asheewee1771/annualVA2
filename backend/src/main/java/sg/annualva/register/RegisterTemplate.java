package sg.annualva.register;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The fixed Excel template a VA POC fills in to load the System Register (story 1, ADR 0016). One sheet, exactly five
 * columns in this order, a header comment on each saying what goes there, a drop-down on Quarter and a shape check on
 * both email columns, and one example row that is plainly a sample. Every column is formatted as text so that a
 * System ID such as {@code 007} keeps its leading zeros. The upload that reads the file back (ticket 08) matches the
 * header text below, so changing it is a change to that contract.
 */
final class RegisterTemplate {

    static final String SHEET = "System Register";

    /** The five column headers, in order; the upload matches them as written. */
    static final List<String> HEADERS = List.of(
            "System ID", "System Name", "SMO/Tech Lead email", "Cyber Manager email", "Quarter");

    /** The Quarter values the template accepts: the Quarter of the VA Cycle in which the System does its VA. */
    static final List<String> QUARTERS = List.of("Q1", "Q2", "Q3", "Q4");

    private static final List<String> HINTS = List.of(
            "Required. The System's own identifier. It is how a re-upload recognises the System, so keep it the same"
                    + " every time and never reuse it for another System. Text only.",
            "Required. The System's name as people know it.",
            "Required. The email address of the one SMO/Tech Lead accountable for this System.",
            "Required. The email address of the Cyber Manager who reviews this System. It must be a different person"
                    + " from the SMO/Tech Lead.",
            "Required. The Quarter of the VA Cycle in which this System does its VA: Q1, Q2, Q3 or Q4.");

    /** A row that reads as a sample and is not a real System; delete it before uploading. */
    private static final List<String> EXAMPLE = List.of(
            "EXAMPLE-001", "Example: delete this row before uploading", "smo.example@example.org",
            "cyber.manager.example@example.org", "Q1");

    private static final int[] COLUMN_WIDTHS = {16, 44, 32, 32, 12};
    private static final int FIRST_DATA_ROW = 1;
    private static final int LAST_ROW = SpreadsheetVersion.EXCEL2007.getLastRowIndex();
    private static final int SMO_EMAIL = 2;
    private static final int CYBER_MANAGER_EMAIL = 3;
    private static final int QUARTER = 4;

    private final Clock clock;

    RegisterTemplate(Clock clock) {
        this.clock = clock;
    }

    byte[] build() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.getProperties().getCoreProperties().setCreator("Annual VA");
            workbook.getProperties().getCoreProperties().setTitle("System Register template");
            workbook.getProperties().getCoreProperties().setCreated(Optional.of(Date.from(clock.instant())));
            XSSFSheet sheet = workbook.createSheet(SHEET);
            XSSFCellStyle text = textStyle(workbook);
            for (int column = 0; column < HEADERS.size(); column++) {
                sheet.setDefaultColumnStyle(column, text);
                sheet.setColumnWidth(column, COLUMN_WIDTHS[column] * 256);
            }
            writeHeader(workbook, sheet);
            writeExample(workbook, sheet.createRow(FIRST_DATA_ROW), text);
            addChecks(sheet);
            sheet.createFreezePane(0, 1);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static XSSFCellStyle textStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("@"));
        return style;
    }

    private static void writeHeader(XSSFWorkbook workbook, XSSFSheet sheet) {
        XSSFCellStyle style = textStyle(workbook);
        Font bold = workbook.createFont();
        bold.setBold(true);
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        CreationHelper helper = workbook.getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        Row row = sheet.createRow(0);
        for (int column = 0; column < HEADERS.size(); column++) {
            Cell cell = row.createCell(column);
            cell.setCellValue(HEADERS.get(column));
            cell.setCellStyle(style);
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(column + 1);
            anchor.setCol2(column + 3);
            anchor.setRow1(0);
            anchor.setRow2(4);
            Comment hint = drawing.createCellComment(anchor);
            hint.setString(helper.createRichTextString(HINTS.get(column)));
            hint.setAuthor("Annual VA");
            cell.setCellComment(hint);
        }
    }

    private static void writeExample(XSSFWorkbook workbook, Row row, XSSFCellStyle text) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.cloneStyleFrom(text);
        Font sample = workbook.createFont();
        sample.setItalic(true);
        sample.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(sample);
        for (int column = 0; column < EXAMPLE.size(); column++) {
            Cell cell = row.createCell(column);
            cell.setCellValue(EXAMPLE.get(column));
            cell.setCellStyle(style);
        }
    }

    /** Warns on a Quarter that is not one of the four and on an email that cannot be one; the upload checks again. */
    private static void addChecks(XSSFSheet sheet) {
        DataValidationHelper checks = sheet.getDataValidationHelper();
        DataValidation quarter = checks.createValidation(
                checks.createExplicitListConstraint(QUARTERS.toArray(String[]::new)), rowsOf(QUARTER));
        quarter.createPromptBox("Quarter", "Choose Q1, Q2, Q3 or Q4.");
        quarter.createErrorBox("Not a Quarter", "The Quarter must be Q1, Q2, Q3 or Q4.");
        quarter.setSuppressDropDownArrow(true);
        add(sheet, quarter);
        add(sheet, email(checks, SMO_EMAIL, "C2", "SMO/Tech Lead email"));
        add(sheet, email(checks, CYBER_MANAGER_EMAIL, "D2", "Cyber Manager email"));
    }

    private static DataValidation email(DataValidationHelper checks, int column, String firstCell, String name) {
        DataValidation email = checks.createValidation(
                checks.createCustomConstraint("AND(ISNUMBER(FIND(\"@\"," + firstCell + ")),ISERROR(FIND(\" \","
                        + firstCell + ")))"),
                rowsOf(column));
        email.createPromptBox(name, "One email address, such as name@example.org.");
        email.createErrorBox("Not an email address", "Enter one email address, such as name@example.org.");
        return email;
    }

    private static void add(XSSFSheet sheet, DataValidation check) {
        check.setEmptyCellAllowed(true);
        check.setShowPromptBox(true);
        check.setShowErrorBox(true);
        sheet.addValidationData(check);
    }

    private static CellRangeAddressList rowsOf(int column) {
        return new CellRangeAddressList(FIRST_DATA_ROW, LAST_ROW, column, column);
    }
}
