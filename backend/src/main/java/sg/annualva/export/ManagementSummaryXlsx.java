package sg.annualva.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;

/**
 * The management summary as an Excel workbook (stories 81 and 86): a Summary sheet of counts by Quarter and state with
 * Endorsed Late, and a Systems sheet with one row per System, in the words of {@link SummaryText}. Each sheet carries its
 * classification in its header, its footer and its first row, so a printed page, a screen and a copied range all show
 * it. Every text is written as a string cell, never a formula, so a System name that starts with {@code =} is shown as
 * typed.
 */
final class ManagementSummaryXlsx {

    private ManagementSummaryXlsx() {}

    static byte[] render(ManagementSummary summary) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.getProperties().getCoreProperties().setTitle(SummaryText.title(summary));
            workbook.getProperties().getCoreProperties().setCreator("Annual VA");
            XSSFCellStyle bold = bold(workbook);
            writeSummary(classified(workbook, SummaryText.SUMMARY, summary.classification(), bold), summary, bold);
            writeSystems(classified(workbook, SummaryText.SYSTEMS, summary.classification(), bold), summary, bold);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A sheet whose header, footer and first row carry the classification. */
    private static XSSFSheet classified(XSSFWorkbook workbook, String name, Classification classification,
            XSSFCellStyle bold) {
        XSSFSheet sheet = workbook.createSheet(name);
        sheet.getHeader().setCenter(classification.label());
        sheet.getFooter().setCenter(classification.label());
        sheet.getFooter().setRight("Page &P of &N");
        text(sheet.createRow(0), List.of(classification.label()), bold);
        return sheet;
    }

    private static void writeSummary(XSSFSheet sheet, ManagementSummary summary, XSSFCellStyle bold) {
        text(sheet.createRow(1), List.of(SummaryText.title(summary)), bold);
        text(sheet.createRow(2), List.of(SummaryText.asAt(summary)), null);
        List<String> headers = SummaryText.quarterHeaders();
        text(sheet.createRow(4), headers, bold);
        int r = 5;
        for (ManagementSummary.QuarterLine strip : summary.quarters()) {
            Row row = sheet.createRow(r++);
            text(row, SummaryText.quarterTexts(strip), null);
            int c = SummaryText.QUARTER_TEXTS;
            for (long count : SummaryText.quarterCounts(strip)) {
                row.createCell(c++).setCellValue(count);
            }
        }
        for (int c = 0; c < headers.size(); c++) {
            sheet.setColumnWidth(c, (c < SummaryText.QUARTER_TEXTS ? 12 : 14) * 256);
        }
    }

    private static void writeSystems(XSSFSheet sheet, ManagementSummary summary, XSSFCellStyle bold) {
        text(sheet.createRow(1), SummaryText.SYSTEM_HEADERS, bold);
        int r = 2;
        for (ManagementSummary.SystemLine line : summary.systems()) {
            Row row = sheet.createRow(r++);
            text(row, SummaryText.systemTexts(line), null);
            row.createCell(SummaryText.SYSTEM_HEADERS.size() - 1).setCellValue(line.openDeviations());
        }
        int[] widths = {16, 36, 9, 32, 22, 10, 14, 16};
        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, widths[c] * 256);
        }
        sheet.createFreezePane(0, 2);
    }

    /** Writes the texts from the first column on, each as a string cell. */
    private static void text(Row row, List<String> texts, @Nullable XSSFCellStyle style) {
        for (int c = 0; c < texts.size(); c++) {
            var cell = row.createCell(c);
            cell.setCellValue(texts.get(c));
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    private static XSSFCellStyle bold(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
