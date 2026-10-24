package sg.annualva.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;
import sg.annualva.dashboard.SystemState;

/**
 * The management summary as an Excel workbook (stories 81 and 86): a Summary sheet of counts by Quarter and state with
 * Endorsed Late, and a Systems sheet with one row per System. Each sheet carries its classification in its header, its
 * footer and its first row, so a printed page, a screen and a copied range all show it. Every text is written as a
 * string cell, never a formula, so a System name that starts with {@code =} is shown as typed.
 */
final class ManagementSummaryXlsx {

    static final String SUMMARY = "Summary";
    static final String SYSTEMS = "Systems";

    /** The states counted, in the order of the spec's state table: every one but Withdrawn, which is never counted. */
    private static final List<SystemState> COUNTED =
            Arrays.stream(SystemState.values()).filter(state -> state != SystemState.WITHDRAWN).toList();

    private static final List<String> SYSTEM_HEADERS = List.of("System ID", "System Name", "Quarter", "SMO/Tech Lead",
            "State", "Overdue", "Endorsed Late", "Open Deviations");

    private ManagementSummaryXlsx() {}

    static byte[] render(ManagementSummary summary) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.getProperties().getCoreProperties().setTitle(title(summary));
            workbook.getProperties().getCoreProperties().setCreator("Annual VA");
            XSSFCellStyle bold = bold(workbook);
            writeSummary(classified(workbook, SUMMARY, summary.classification(), bold), summary, bold);
            writeSystems(classified(workbook, SYSTEMS, summary.classification(), bold), summary, bold);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "Management summary, VA Cycle 2027", with ", Q3" when it covers one Quarter. */
    static String title(ManagementSummary summary) {
        return "Management summary, VA Cycle " + summary.cycle()
                + (summary.quarter() == null ? "" : ", Q" + summary.quarter());
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
        text(sheet.createRow(1), List.of(title(summary)), bold);
        text(sheet.createRow(2), List.of("As at " + summary.asAt()), null);
        List<String> headers = new ArrayList<>(List.of("Quarter", "Starts", "Ends"));
        for (SystemState state : COUNTED) {
            headers.add(label(state));
            if (state == SystemState.ENDORSED) {
                headers.add("Endorsed Late");
            }
        }
        headers.add("Total");
        text(sheet.createRow(4), headers, bold);
        int r = 5;
        for (ManagementSummary.QuarterLine strip : summary.quarters()) {
            Row row = sheet.createRow(r++);
            text(row, List.of("Q" + strip.quarter(), strip.startsOn().toString(), strip.endsOn().toString()), null);
            int c = 3;
            for (SystemState state : COUNTED) {
                row.createCell(c++).setCellValue(strip.counts().getOrDefault(state, 0L));
                if (state == SystemState.ENDORSED) {
                    row.createCell(c++).setCellValue(strip.endorsedLate());
                }
            }
            row.createCell(c).setCellValue(strip.total());
        }
        for (int c = 0; c < headers.size(); c++) {
            sheet.setColumnWidth(c, (c < 3 ? 12 : 14) * 256);
        }
    }

    private static void writeSystems(XSSFSheet sheet, ManagementSummary summary, XSSFCellStyle bold) {
        text(sheet.createRow(1), SYSTEM_HEADERS, bold);
        int r = 2;
        for (ManagementSummary.SystemLine line : summary.systems()) {
            Row row = sheet.createRow(r++);
            text(row, List.of(line.systemId(), line.name(), "Q" + line.quarter(), line.smoEmail(), label(line.state()),
                    yesNo(line.overdue()), yesNo(line.endorsedLate())), null);
            row.createCell(SYSTEM_HEADERS.size() - 1).setCellValue(line.openDeviations());
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

    private static String yesNo(boolean flag) {
        return flag ? "Yes" : "No";
    }

    /** A state as the glossary names it. */
    static String label(SystemState state) {
        return switch (state) {
            case NOT_STARTED -> "Not Started";
            case DRAFT -> "Draft";
            case IN_REVIEW -> "In Review";
            case RETURNED_FOR_REWORK -> "Returned for Rework";
            case AWAITING_ENDORSEMENT -> "Awaiting Endorsement";
            case ENDORSED -> "Endorsed";
            case EXEMPTION_REQUESTED -> "Exemption Requested";
            case EXEMPTED -> "Exempted";
            case WITHDRAWN -> "Withdrawn";
            case NOT_ENDORSED -> "Not Endorsed";
        };
    }
}
