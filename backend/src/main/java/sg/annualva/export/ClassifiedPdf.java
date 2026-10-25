package sg.annualva.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.jspecify.annotations.Nullable;

/**
 * A PDF whose every page carries its classification (story 86): centred in the header and the footer, with the page
 * number beside the footer, as the first line of its content, and as a diagonal, semi-transparent watermark across the
 * page, drawn over the content so nothing on the page can hide it. Content is written top to bottom as lines of text and
 * {@link ExportTable}s whose cells wrap; a table starts on a new page unless its heading, header row and first row fit,
 * and a table that reaches the bottom of a page goes on to the next under its header row again.
 *
 * <p>Pages are A4 landscape. Text is set in the standard Helvetica, which the PDF names and does not embed, so the file
 * stays small and holds no font program; a character Helvetica cannot draw (outside Windows-1252, such as Chinese or
 * Tamil script) is written as {@code ?} where the text comes in, and the Excel workbook of the same summary keeps it as
 * typed (ADR 0054).
 */
final class ClassifiedPdf implements AutoCloseable {

    private static final PDRectangle PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float SIDE = 36;
    /** The content keeps this far from the top and the bottom edge, which belong to the header and the footer. */
    private static final float CONTENT_MARGIN = 50;
    private static final float STAMP_BASELINE = 22;
    private static final float HEADER_SIZE = 10;
    private static final float FOOTER_SIZE = 9;
    /** A line of text takes this many times its font size, and its baseline sits this far up from its foot. */
    private static final float LINE_LEADING = 1.4f;
    private static final float LINE_DESCENT = 0.3f;
    private static final float HEADING_SIZE = 11;
    private static final float CELL_SIZE = 8;
    private static final float CELL_LEADING = 10;
    private static final float CELL_PAD = 3;
    /** Space under a row's last line, above its rule. */
    private static final float ROW_GAP = 2;
    private static final float HEADER_RULE = 1f;
    private static final float ROW_RULE = 0.25f;
    private static final float WATERMARK_OPACITY = 0.15f;
    private static final float WATERMARK_MAX_SIZE = 96;
    /** How much of the page's diagonal the watermark spans. */
    private static final double WATERMARK_SPAN = 0.7;
    /** How far below the diagonal the watermark's baseline sits, as a part of its size, so its letters straddle it. */
    private static final double WATERMARK_DROP = 1.0 / 3;
    private static final char UNDRAWABLE = '?';

    private final PDDocument document = new PDDocument();
    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final String label;
    private @Nullable PDPageContentStream content;
    private float y;

    ClassifiedPdf(String title, Classification classification) {
        this.label = drawable(classification.label());
        document.getDocumentInformation().setTitle(drawable(title));
        document.getDocumentInformation().setCreator("Annual VA");
        try {
            nextPage();
        } catch (IOException e) {
            close();
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /** One line of bold text. */
    void heading(String text, float size) {
        unchecked(() -> line(drawable(text), bold, size));
    }

    /** One line of plain text. */
    void text(String text, float size) {
        unchecked(() -> line(drawable(text), regular, size));
    }

    /** Leaves this much space. */
    void gap(float points) {
        y -= points;
    }

    /**
     * A table under its name: a bold header row, then its rows, each cell wrapped to its column, the page's width shared
     * out in the proportions of the columns' widths. It starts on a new page unless its name, header row and first row
     * fit; a row that does not fit goes to the next page, under the header row again.
     */
    void table(ExportTable table) {
        unchecked(() -> {
            float[] widths = widths(table.columns());
            List<List<String>> header = wrapped(widths, table.headers().stream().map(this::drawable).toList(), bold);
            List<List<List<String>>> rows = new ArrayList<>();
            for (List<ExportTable.Cell> cells : table.rows()) {
                rows.add(wrapped(widths, cells.stream().map(cell -> drawable(cell.shown())).toList(), regular));
            }
            float first = rows.isEmpty() ? 0 : height(rows.getFirst());
            if (y - HEADING_SIZE * LINE_LEADING - height(header) - first < CONTENT_MARGIN) {
                nextPage();
            }
            line(drawable(table.name()), bold, HEADING_SIZE);
            draw(widths, header, bold, HEADER_RULE);
            for (List<List<String>> row : rows) {
                if (y - height(row) < CONTENT_MARGIN) {
                    nextPage();
                    draw(widths, header, bold, HEADER_RULE);
                }
                draw(widths, row, regular, ROW_RULE);
            }
        });
    }

    /** Stamps every page with the classification and its number, and answers the file. */
    byte[] finish() {
        try {
            closeContent();
            int count = document.getNumberOfPages();
            for (int p = 0; p < count; p++) {
                stamp(document.getPage(p), p + 1, count);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            try {
                closeContent();
            } finally {
                document.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Some drawing, its {@link IOException} answered as unchecked at the edge of this class. */
    private interface Drawing {
        void run() throws IOException;
    }

    private static void unchecked(Drawing drawing) {
        try {
            drawing.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void closeContent() throws IOException {
        if (content != null) {
            content.close();
            content = null;
        }
    }

    private PDPageContentStream content() {
        if (content == null) {
            throw new IllegalStateException("the PDF is finished");
        }
        return content;
    }

    /** Starts a page whose first line states the classification, as each sheet's first row does in the workbook. */
    private void nextPage() throws IOException {
        closeContent();
        PDPage page = new PDPage(PAGE);
        document.addPage(page);
        y = PAGE.getHeight() - CONTENT_MARGIN;
        content = new PDPageContentStream(document, page);
        line(label, bold, HEADER_SIZE);
    }

    private void line(String drawable, PDType1Font font, float size) throws IOException {
        float leading = size * LINE_LEADING;
        if (y - leading < CONTENT_MARGIN) {
            nextPage();
        }
        y -= leading;
        write(content(), font, size, SIDE, y + size * LINE_DESCENT, drawable);
    }

    /** The columns' widths in points, the content's width shared out in their proportions. */
    private static float[] widths(List<ExportTable.Column> columns) {
        double total = columns.stream().mapToDouble(ExportTable.Column::width).sum();
        float[] widths = new float[columns.size()];
        for (int c = 0; c < widths.length; c++) {
            widths[c] = (float) ((PAGE.getWidth() - 2 * SIDE) * columns.get(c).width() / total);
        }
        return widths;
    }

    private List<List<String>> wrapped(float[] widths, List<String> cells, PDType1Font font) throws IOException {
        List<List<String>> wrapped = new ArrayList<>();
        for (int c = 0; c < cells.size(); c++) {
            wrapped.add(wrap(cells.get(c), font, widths[c] - 2 * CELL_PAD));
        }
        return wrapped;
    }

    private static float height(List<List<String>> wrapped) {
        int lines = wrapped.stream().mapToInt(List::size).max().orElse(1);
        return 2 * CELL_PAD + CELL_SIZE + (lines - 1) * CELL_LEADING + ROW_GAP;
    }

    /** One row of wrapped cells, then a rule under it this thick. */
    private void draw(float[] widths, List<List<String>> wrapped, PDType1Font font, float rule) throws IOException {
        PDPageContentStream page = content();
        float x = SIDE;
        for (int c = 0; c < wrapped.size(); c++) {
            List<String> lines = wrapped.get(c);
            for (int l = 0; l < lines.size(); l++) {
                write(page, font, CELL_SIZE, x + CELL_PAD, y - CELL_PAD - CELL_SIZE - l * CELL_LEADING, lines.get(l));
            }
            x += widths[c];
        }
        y -= height(wrapped);
        page.setLineWidth(rule);
        page.moveTo(SIDE, y);
        page.lineTo(x, y);
        page.stroke();
    }

    /** Breaks a drawable text into lines no wider than this, between words, and inside a word only when it is wider alone. */
    private static List<String> wrap(String drawable, PDType1Font font, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : drawable.split(" ")) {
            String joined = line.isEmpty() ? word : line + " " + word;
            if (width(font, CELL_SIZE, joined) <= width) {
                line.setLength(0);
                line.append(joined);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            for (int cp : word.codePoints().toArray()) {
                if (!line.isEmpty() && width(font, CELL_SIZE, line + Character.toString(cp)) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.appendCodePoint(cp);
            }
        }
        lines.add(line.toString());
        return lines;
    }

    /** The header, the footer with the page number, and the watermark, drawn over what the page holds. */
    private void stamp(PDPage page, int number, int count) throws IOException {
        try (PDPageContentStream stamp =
                new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            float middle = PAGE.getWidth() / 2;
            write(stamp, bold, HEADER_SIZE, middle - width(bold, HEADER_SIZE, label) / 2,
                    PAGE.getHeight() - STAMP_BASELINE, label);
            write(stamp, regular, FOOTER_SIZE, middle - width(regular, FOOTER_SIZE, label) / 2, STAMP_BASELINE, label);
            String pageOf = "Page " + number + " of " + count;
            write(stamp, regular, FOOTER_SIZE, PAGE.getWidth() - SIDE - width(regular, FOOTER_SIZE, pageOf),
                    STAMP_BASELINE, pageOf);
            watermark(stamp);
        }
    }

    /** The classification once across the page, corner to corner, at {@value #WATERMARK_OPACITY} opacity. */
    private void watermark(PDPageContentStream stamp) throws IOException {
        double angle = Math.atan2(PAGE.getHeight(), PAGE.getWidth());
        double diagonal = Math.hypot(PAGE.getWidth(), PAGE.getHeight());
        float size = (float) Math.min(WATERMARK_MAX_SIZE, diagonal * WATERMARK_SPAN / width(bold, 1, label));
        double half = width(bold, size, label) / 2;
        PDExtendedGraphicsState translucent = new PDExtendedGraphicsState();
        translucent.setNonStrokingAlphaConstant(WATERMARK_OPACITY);
        stamp.setGraphicsStateParameters(translucent);
        stamp.setNonStrokingColor(0.5f);
        stamp.beginText();
        stamp.setFont(bold, size);
        double drop = size * WATERMARK_DROP;
        float x = (float) (PAGE.getWidth() / 2 - half * Math.cos(angle) + drop * Math.sin(angle));
        float baseline = (float) (PAGE.getHeight() / 2 - half * Math.sin(angle) - drop * Math.cos(angle));
        stamp.setTextMatrix(Matrix.getRotateInstance(angle, x, baseline));
        stamp.showText(label);
        stamp.endText();
    }

    private static void write(PDPageContentStream stream, PDType1Font font, float size, float x, float baseline,
            String drawable) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, baseline);
        stream.showText(drawable);
        stream.endText();
    }

    private static float width(PDType1Font font, float size, String drawable) throws IOException {
        return font.getStringWidth(drawable) / 1000 * size;
    }

    /**
     * The text with every character Helvetica cannot draw written as {@value #UNDRAWABLE}: done once, where text comes
     * in, so everything inside this class draws and measures it as it is. Both Helvetica faces draw the same characters.
     */
    private String drawable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> out.append(drawable(cp) ? Character.toString(cp) : UNDRAWABLE));
        return out.toString();
    }

    private boolean drawable(int codePoint) {
        try {
            regular.encode(Character.toString(codePoint));
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }
}
