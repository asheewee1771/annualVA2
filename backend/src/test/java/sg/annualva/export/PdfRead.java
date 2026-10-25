package sg.annualva.export;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

/**
 * A downloaded PDF read the way a person reads it: the text of each page, of its top and bottom margins, and the text
 * drawn at an angle (the watermark), with whether the page draws anything semi-transparent.
 *
 * @param pages one entry per page
 */
record PdfRead(List<Page> pages) {

    /** How far from the top and the bottom edge the header and the footer are looked for, in points. */
    private static final float MARGIN = 40;

    /** The side margins nothing upright may reach into, in points. */
    private static final float SIDE = 30;

    /**
     * One page.
     *
     * @param text everything on it but the watermark
     * @param header the text in its top margin
     * @param footer the text in its bottom margin
     * @param slanted the text drawn neither horizontally nor vertically
     * @param translucent whether it fills anything with an opacity below 1
     * @param illegible the upright characters drawn over another on the same line, or past the page's side margins
     */
    record Page(String text, String header, String footer, String slanted, boolean translucent, String illegible) {}

    static PdfRead of(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<Page> pages = new ArrayList<>();
            for (int p = 0; p < document.getNumberOfPages(); p++) {
                pages.add(page(document, p));
            }
            return new PdfRead(List.copyOf(pages));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All the text of every page, in order. */
    String text() {
        StringBuilder all = new StringBuilder();
        pages.forEach(page -> all.append(page.text()).append('\n'));
        return all.toString();
    }

    private static Page page(PDDocument document, int index) throws IOException {
        PDPage page = document.getPage(index);
        Upright whole = new Upright();
        whole.setSortByPosition(true);
        whole.setStartPage(index + 1);
        whole.setEndPage(index + 1);
        String text = whole.getText(document);

        float width = page.getMediaBox().getWidth();
        float height = page.getMediaBox().getHeight();
        PDFTextStripperByArea margins = new UprightArea();
        margins.setSortByPosition(true);
        margins.addRegion("header", new Rectangle2D.Float(0, 0, width, MARGIN));
        margins.addRegion("footer", new Rectangle2D.Float(0, height - MARGIN, width, MARGIN));
        margins.extractRegions(page);

        Slanted slanted = new Slanted();
        slanted.setStartPage(index + 1);
        slanted.setEndPage(index + 1);
        slanted.getText(document);

        return new Page(text, margins.getTextForRegion("header").strip(), margins.getTextForRegion("footer").strip(),
                slanted.text.toString(), translucent(page), illegible(whole.positions, width));
    }

    /**
     * The characters that start before the one to their left on the same line has ended, or that reach into the side
     * margins: a cell's text that ran into the next cell or off the page.
     */
    private static String illegible(List<TextPosition> positions, float pageWidth) {
        List<TextPosition> sorted = new ArrayList<>(positions);
        sorted.sort(java.util.Comparator.comparing(TextPosition::getYDirAdj).thenComparing(TextPosition::getXDirAdj));
        StringBuilder found = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            TextPosition here = sorted.get(i);
            boolean offPage = here.getXDirAdj() < SIDE || here.getXDirAdj() + here.getWidthDirAdj() > pageWidth - SIDE;
            TextPosition left = i == 0 ? null : sorted.get(i - 1);
            boolean overlaps = left != null && Math.abs(left.getYDirAdj() - here.getYDirAdj()) < 0.5f
                    && here.getXDirAdj() < left.getXDirAdj() + left.getWidthDirAdj() - 0.5f;
            if (offPage || overlaps) {
                found.append(here.getUnicode());
            }
        }
        return found.toString();
    }

    private static boolean translucent(PDPage page) {
        for (COSName name : page.getResources().getExtGStateNames()) {
            PDExtendedGraphicsState state = page.getResources().getExtGState(name);
            Float alpha = state.getNonStrokingAlphaConstant();
            if (alpha != null && alpha < 1f) {
                return true;
            }
        }
        return false;
    }

    /** Whether the text matrix turns this character by an angle that is not a multiple of 90 degrees. */
    private static boolean slanted(TextPosition position) {
        var matrix = position.getTextMatrix();
        return matrix.getShearX() != 0 && matrix.getScaleX() != 0;
    }

    /** Collects the slanted characters. */
    private static final class Slanted extends PDFTextStripper {
        private final StringBuilder text = new StringBuilder();

        @Override
        protected void processTextPosition(TextPosition position) {
            if (slanted(position)) {
                text.append(position.getUnicode());
            }
        }
    }

    /** Reads the text a person reads across the page, leaving the watermark out. */
    private static final class Upright extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();

        @Override
        protected void processTextPosition(TextPosition position) {
            if (!slanted(position)) {
                positions.add(position);
                super.processTextPosition(position);
            }
        }
    }

    /** Reads the text of regions, leaving the watermark out. */
    private static final class UprightArea extends PDFTextStripperByArea {
        UprightArea() throws IOException {
            super();
        }

        @Override
        protected void processTextPosition(TextPosition position) {
            if (!slanted(position)) {
                super.processTextPosition(position);
            }
        }
    }
}
