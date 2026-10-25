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
 * The management summary as an Excel workbook (stories 81 and 86): one sheet per table of {@link SummaryText}, Summary
 * (under the title and the date it was read on) and Systems. Each sheet carries its classification in its header, its
 * footer and its first row, so a printed page, a screen and a copied range all show it. Every text is written as a
 * string cell, never a formula, so a System name that starts with {@code =} is shown as typed; a count is a number.
 */
final class ManagementSummaryXlsx {

    private ManagementSummaryXlsx() {}

    static byte[] render(ManagementSummary summary) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.getProperties().getCoreProperties().setTitle(SummaryText.title(summary));
            workbook.getProperties().getCoreProperties().setCreator("Annual VA");
            XSSFCellStyle bold = bold(workbook);
            ExportTable quarters = SummaryText.quarters(summary);
            XSSFSheet first = classified(workbook, quarters.name(), summary.classification(), bold);
            text(first.createRow(1), SummaryText.title(summary), bold);
            text(first.createRow(2), SummaryText.asAt(summary), null);
            write(first, 4, quarters, bold);
            ExportTable systems = SummaryText.systems(summary);
            write(classified(workbook, systems.name(), summary.classification(), bold), 1, systems, bold);
            workbook.getSheet(systems.name()).createFreezePane(0, 2);
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
        text(sheet.createRow(0), classification.label(), bold);
        return sheet;
    }

    /** The table's header row at this row, in bold, and its rows under it; each column as wide as the table says. */
    private static void write(XSSFSheet sheet, int headerRow, ExportTable table, XSSFCellStyle bold) {
        List<String> headers = table.headers();
        Row header = sheet.createRow(headerRow);
        for (int c = 0; c < headers.size(); c++) {
            var cell = header.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(bold);
            sheet.setColumnWidth(c, (int) Math.round(table.columns().get(c).width() * 256));
        }
        int r = headerRow + 1;
        for (List<ExportTable.Cell> cells : table.rows()) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < cells.size(); c++) {
                var cell = row.createCell(c);
                switch (cells.get(c)) {
                    case ExportTable.Cell.Text text -> cell.setCellValue(text.value());
                    case ExportTable.Cell.Count count -> cell.setCellValue(count.value());
                }
            }
        }
    }

    /** Writes one text in the row's first cell, as a string cell. */
    private static void text(Row row, String text, @Nullable XSSFCellStyle style) {
        var cell = row.createCell(0);
        cell.setCellValue(text);
        if (style != null) {
            cell.setCellStyle(style);
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
