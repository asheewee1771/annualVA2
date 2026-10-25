package sg.annualva.export;

/**
 * The management summary as a PDF (stories 81 and 86, ticket 45): the same tables as the Excel workbook, from
 * {@link SummaryText}. The title and the date it was read on, then the Summary table of counts by Quarter and state with
 * Endorsed Late, then the Systems table with one row per System. Every page carries the classification in its header,
 * its footer and its first line, and as a diagonal watermark ({@link ClassifiedPdf}). Nothing from a Finding is in what
 * it is given, so none can be written.
 */
final class ManagementSummaryPdf {

    private static final float TITLE_SIZE = 14;
    private static final float TEXT_SIZE = 10;
    private static final float BEFORE_TABLE = 8;

    private ManagementSummaryPdf() {}

    static byte[] render(ManagementSummary summary) {
        try (ClassifiedPdf pdf = new ClassifiedPdf(SummaryText.title(summary), summary.classification())) {
            pdf.heading(SummaryText.title(summary), TITLE_SIZE);
            pdf.text(SummaryText.asAt(summary), TEXT_SIZE);
            pdf.gap(BEFORE_TABLE);
            pdf.table(SummaryText.quarters(summary));
            pdf.gap(BEFORE_TABLE);
            pdf.table(SummaryText.systems(summary));
            return pdf.finish();
        }
    }
}
