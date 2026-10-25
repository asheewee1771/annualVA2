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

/**
 * A PDF whose every page carries its classification (story 86): centred in the header and the footer, with the page
 * number beside the footer, as the first line of its content, and as a diagonal, semi-transparent watermark across the
 * page, drawn over the content so nothing on the page can hide it. Content is written top to bottom as lines of text and tables whose cells wrap; a table
 * that reaches the bottom of a page goes on to the next, with its header row again.
 *
 * <p>Pages are A4 landscape. Text is set in the standard Helvetica, which the PDF names and does not embed, so the file
 * stays small and holds no font program; a character Helvetica cannot draw (outside Windows-1252, such as Chinese or
 * Tamil script) is written as {@code ?}, and the Excel workbook of the same summary keeps it as typed (ADR 0053).
 */
final class ClassifiedPdf implements AutoCloseable {

    private static final PDRectangle PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float SIDE = 36;
    /** The content keeps this far from the top and the bottom edge, which belong to the header and the footer. */
    private static final float CONTENT_MARGIN = 50;
    private static final float STAMP_BASELINE = 22;
    private static final float CELL_SIZE = 8;
    private static final float CELL_LEADING = 10;
    private static final float CELL_PAD = 3;
    private static final float WATERMARK_OPACITY = 0.15f;
    private static final float WATERMARK_MAX_SIZE = 96;
    private static final char UNDRAWABLE = '?';

    private final PDDocument document = new PDDocument();
    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final String label;
    private PDPageContentStream content;
    private float y;

    ClassifiedPdf(String title, Classification classification) {
        this.label = classification.label();
        document.getDocumentInformation().setTitle(title);
        document.getDocumentInformation().setCreator("Annual VA");
        nextPage();
    }

    /** The width content may use, between the side margins. */
    static float width() {
        return PAGE.getWidth() - 2 * SIDE;
    }

    /** One line of bold text. */
    void heading(String text, float size) {
        line(text, bold, size);
    }

    /** One line of plain text. */
    void text(String text, float size) {
        line(text, regular, size);
    }

    /** Leaves this much space. */
    void gap(float points) {
        y -= points;
    }

    /**
     * A table: a bold header row, then one row per entry, each cell wrapped to its column's width. A row that does not
     * fit goes to the next page, under the header row again.
     */
    void table(float[] widths, List<String> headers, List<List<String>> rows) {
        row(widths, headers, bold, 1f);
        for (List<String> cells : rows) {
            if (y - height(widths, cells, regular) < CONTENT_MARGIN) {
                nextPage();
                row(widths, headers, bold, 1f);
            }
            row(widths, cells, regular, 0.25f);
        }
    }

    /** Stamps every page with the classification and its number, and answers the file. */
    byte[] finish() {
        try {
            content.close();
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
            content.close();
            document.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void line(String text, PDType1Font font, float size) {
        float leading = size * 1.4f;
        if (y - leading < CONTENT_MARGIN) {
            nextPage();
        }
        y -= leading;
        write(content, font, size, SIDE, y + size * 0.3f, text);
    }

    /** Starts a page whose first line states the classification, as each sheet's first row does in the workbook. */
    private void nextPage() {
        try {
            if (document.getNumberOfPages() > 0) {
                content.close();
            }
            PDPage page = new PDPage(PAGE);
            document.addPage(page);
            y = PAGE.getHeight() - CONTENT_MARGIN;
            content = new PDPageContentStream(document, page);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        line(label, bold, 10);
    }

    private void row(float[] widths, List<String> cells, PDType1Font font, float rule) {
        List<List<String>> wrapped = new ArrayList<>();
        for (int c = 0; c < cells.size(); c++) {
            wrapped.add(wrap(cells.get(c), font, widths[c] - 2 * CELL_PAD));
        }
        float height = height(wrapped);
        float x = SIDE;
        for (int c = 0; c < wrapped.size(); c++) {
            List<String> lines = wrapped.get(c);
            for (int l = 0; l < lines.size(); l++) {
                write(content, font, CELL_SIZE, x + CELL_PAD, y - CELL_PAD - CELL_SIZE - l * CELL_LEADING, lines.get(l));
            }
            x += widths[c];
        }
        y -= height;
        try {
            content.setLineWidth(rule);
            content.moveTo(SIDE, y);
            content.lineTo(x, y);
            content.stroke();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private float height(float[] widths, List<String> cells, PDType1Font font) {
        List<List<String>> wrapped = new ArrayList<>();
        for (int c = 0; c < cells.size(); c++) {
            wrapped.add(wrap(cells.get(c), font, widths[c] - 2 * CELL_PAD));
        }
        return height(wrapped);
    }

    private static float height(List<List<String>> wrapped) {
        int lines = wrapped.stream().mapToInt(List::size).max().orElse(1);
        return 2 * CELL_PAD + CELL_SIZE + (lines - 1) * CELL_LEADING + 2;
    }

    /** Breaks a text into lines no wider than this, between words, and inside a word only when it is wider alone. */
    private List<String> wrap(String text, PDType1Font font, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : drawable(font, text).split(" ")) {
            String joined = line.isEmpty() ? word : line + " " + word;
            if (width(font, joined) * CELL_SIZE <= width) {
                line.setLength(0);
                line.append(joined);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            for (int cp : word.codePoints().toArray()) {
                String longer = line.toString() + Character.toString(cp);
                if (!line.isEmpty() && width(font, longer) * CELL_SIZE > width) {
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
            write(stamp, bold, 10, middle - width(bold, label) * 10 / 2, PAGE.getHeight() - STAMP_BASELINE, label);
            write(stamp, regular, 9, middle - width(regular, label) * 9 / 2, STAMP_BASELINE, label);
            String pageOf = "Page " + number + " of " + count;
            write(stamp, regular, 9, PAGE.getWidth() - SIDE - width(regular, pageOf) * 9, STAMP_BASELINE, pageOf);
            watermark(stamp);
        }
    }

    /** The classification once across the page, corner to corner, at {@value #WATERMARK_OPACITY} opacity. */
    private void watermark(PDPageContentStream stamp) throws IOException {
        double angle = Math.atan2(PAGE.getHeight(), PAGE.getWidth());
        double diagonal = Math.hypot(PAGE.getWidth(), PAGE.getHeight());
        float size = (float) Math.min(WATERMARK_MAX_SIZE, diagonal * 0.7 / width(bold, label));
        double half = width(bold, label) * size / 2;
        PDExtendedGraphicsState translucent = new PDExtendedGraphicsState();
        translucent.setNonStrokingAlphaConstant(WATERMARK_OPACITY);
        stamp.setGraphicsStateParameters(translucent);
        stamp.setNonStrokingColor(0.5f);
        stamp.beginText();
        stamp.setFont(bold, size);
        float x = (float) (PAGE.getWidth() / 2 - half * Math.cos(angle) + size / 3 * Math.sin(angle));
        float yStart = (float) (PAGE.getHeight() / 2 - half * Math.sin(angle) - size / 3 * Math.cos(angle));
        stamp.setTextMatrix(Matrix.getRotateInstance(angle, x, yStart));
        stamp.showText(label);
        stamp.endText();
    }

    private void write(PDPageContentStream stream, PDType1Font font, float size, float x, float baseline,
            String text) {
        try {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, baseline);
            stream.showText(drawable(font, text));
            stream.endText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text's width at size 1. */
    private static float width(PDType1Font font, String text) {
        try {
            return font.getStringWidth(drawable(font, text)) / 1000;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text with every character the font cannot draw written as {@value #UNDRAWABLE}. */
    private static String drawable(PDType1Font font, String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> out.append(drawable(font, cp) ? Character.toString(cp) : UNDRAWABLE));
        return out.toString();
    }

    private static boolean drawable(PDType1Font font, int codePoint) {
        try {
            font.encode(Character.toString(codePoint));
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }
}
