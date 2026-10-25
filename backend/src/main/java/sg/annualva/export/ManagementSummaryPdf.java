package sg.annualva.export;

import java.util.ArrayList;
import java.util.List;

/**
 * The management summary as a PDF (stories 81 and 86, ticket 45): the same content as the Excel workbook, in the words of
 * {@link SummaryText}. The first line states the classification, then the title and the date it was read on, the Summary
 * table of counts by Quarter and state with Endorsed Late, and the Systems table with one row per System. Every page
 * carries the classification in its header and footer and as a diagonal watermark ({@link ClassifiedPdf}). Nothing from
 * a Finding is in what it is given, so none can be written.
 */
final class ManagementSummaryPdf {

    /** The Systems table's columns, in points; they fill the page's width. */
    private static final float[] SYSTEM_WIDTHS = {90, 200, 45, 170, 105, 50, 55, 55};

    /** The Quarter and its two dates are wider than a count. */
    private static final float[] QUARTER_TEXT_WIDTHS = {36, 51, 51};

    private ManagementSummaryPdf() {}

    static byte[] render(ManagementSummary summary) {
        try (ClassifiedPdf pdf = new ClassifiedPdf(SummaryText.title(summary), summary.classification())) {
            pdf.heading(SummaryText.title(summary), 14);
            pdf.text(SummaryText.asAt(summary), 10);
            pdf.gap(8);
            pdf.heading(SummaryText.SUMMARY, 11);
            List<String> headers = SummaryText.quarterHeaders();
            pdf.table(quarterWidths(headers.size()), headers, summary.quarters().stream().map(ManagementSummaryPdf::cells)
                    .toList());
            pdf.gap(12);
            pdf.heading(SummaryText.SYSTEMS, 11);
            pdf.table(SYSTEM_WIDTHS, SummaryText.SYSTEM_HEADERS, summary.systems().stream()
                    .map(ManagementSummaryPdf::cells).toList());
            return pdf.finish();
        }
    }

    /** The Quarter's texts at their widths, and the counts sharing what is left of the page. */
    private static float[] quarterWidths(int columns) {
        float[] widths = new float[columns];
        float left = ClassifiedPdf.width();
        for (int c = 0; c < QUARTER_TEXT_WIDTHS.length; c++) {
            widths[c] = QUARTER_TEXT_WIDTHS[c];
            left -= widths[c];
        }
        for (int c = QUARTER_TEXT_WIDTHS.length; c < columns; c++) {
            widths[c] = left / (columns - QUARTER_TEXT_WIDTHS.length);
        }
        return widths;
    }

    private static List<String> cells(ManagementSummary.QuarterLine strip) {
        List<String> cells = new ArrayList<>(SummaryText.quarterTexts(strip));
        SummaryText.quarterCounts(strip).forEach(count -> cells.add(Long.toString(count)));
        return cells;
    }

    private static List<String> cells(ManagementSummary.SystemLine line) {
        List<String> cells = new ArrayList<>(SummaryText.systemTexts(line));
        cells.add(Long.toString(line.openDeviations()));
        return cells;
    }
}
