package sg.annualva.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * What a supporting evidence file may be (ticket 18, ADR 0026): a PDF or an HTML report, attached for people to read and
 * never read by the application. The format is decided from the leading bytes alone, never from the file name or the
 * declared type, and the sniff is conservative: a file that is neither is refused. The order is fixed so that a file that
 * looks like both resolves the same way every time: the {@code %PDF-} marker at the very start wins, then an HTML
 * document (after a byte order mark, whitespace, comments and one XML declaration, a {@code <!doctype html} or
 * {@code <html} tag within the first {@value #HTML_WINDOW} bytes), and anything else is not evidence. HTML is hostile
 * active content: nothing here, and nothing after it, renders, parses or follows what it says.
 */
enum EvidenceFormat {
    PDF(20, ".pdf"),
    HTML(10, ".html");

    /** An HTML document must show its doctype or root tag within this many bytes, after any comments before it. */
    static final int HTML_WINDOW = 4096;

    /**
     * How much of the end of a PDF is looked at for {@code /Encrypt}: the trailer, or for a file with a cross-reference
     * stream the dictionary that comes before that stream's data, which can be more than the window behind the end of a big
     * file (a gap ticket 51 closes). A bound, so a large file costs the same as a small one.
     */
    static final int ENCRYPT_WINDOW = 64 * 1024;

    private static final long MEGABYTE = 1024L * 1024L;
    private static final byte[] PDF_MARKER = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ENCRYPT_NAME = ascii("/Encrypt");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] XML_DECLARATION = ascii("<?xml");
    private static final byte[] COMMENT_OPEN = ascii("<!--");

    private final long maxBytes;
    private final String extension;

    EvidenceFormat(long maxMegabytes, String extension) {
        this.maxBytes = maxMegabytes * MEGABYTE;
        this.extension = extension;
    }

    /** The most a file of this format may weigh. */
    long maxBytes() {
        return maxBytes;
    }

    /** The extension a download of this format is offered under, whatever the file was called when it was uploaded. */
    String extension() {
        return extension;
    }

    /**
     * Whether the last {@value #ENCRYPT_WINDOW} bytes of a PDF hold the name {@code /Encrypt}, which is how a password
     * protected one points at its encryption dictionary. The name must end there (whitespace, a delimiter or the end of the
     * file), so {@code /EncryptMetadata} is another name. A byte comparison and nothing more: no PDF reader is used, so a
     * file that hides the entry in an object stream or spells the name with a {@code #} escape is not caught here (the
     * production scanner, ticket 51, is what inspects those).
     */
    static boolean carriesEncryptName(byte[] pdf) {
        int from = Math.max(0, pdf.length - ENCRYPT_WINDOW);
        for (int at = from; at + ENCRYPT_NAME.length <= pdf.length; at++) {
            int next = at + ENCRYPT_NAME.length;
            if (startsWith(pdf, at, ENCRYPT_NAME, false) && (next == pdf.length || endsAName(pdf[next]))) {
                return true;
            }
        }
        return false;
    }

    /** Whitespace and the PDF delimiters {@code ( ) < > [ ] { } / %} end a name; anything else is more of it. */
    private static boolean endsAName(byte value) {
        return isSpace(value) || value == 0 || "()<>[]{}/%".indexOf(value) >= 0;
    }

    /** The format the bytes are, or empty when they are neither a PDF nor an HTML document. */
    static Optional<EvidenceFormat> sniff(byte[] content) {
        if (startsWith(content, 0, PDF_MARKER, false)) {
            return Optional.of(PDF);
        }
        return isHtml(content) ? Optional.of(HTML) : Optional.empty();
    }

    private static boolean isHtml(byte[] content) {
        int end = Math.min(content.length, HTML_WINDOW);
        int at = startsWith(content, 0, UTF8_BOM, false) ? UTF8_BOM.length : 0;
        at = skipWhitespace(content, at, end);
        if (startsWith(content, at, XML_DECLARATION, false)
                && at + XML_DECLARATION.length < end && isSpace(content[at + XML_DECLARATION.length])) {
            at = after(content, at, end, "?>");
            at = at < 0 ? end : skipWhitespace(content, at, end);
        }
        while (at < end && startsWith(content, at, COMMENT_OPEN, false)) {
            at = after(content, at + COMMENT_OPEN.length, end, "-->");
            if (at < 0) {
                return false;
            }
            at = skipWhitespace(content, at, end);
        }
        return tagAt(content, at, end, "<!doctype html", false) || tagAt(content, at, end, "<html", true);
    }

    /** The tag at {@code at}, ended by whitespace, {@code >} (and {@code /} for an element) rather than more name. */
    private static boolean tagAt(byte[] content, int at, int end, String tag, boolean slashEnds) {
        byte[] wanted = ascii(tag);
        int next = at + wanted.length;
        if (next >= end || !startsWith(content, at, wanted, true)) {
            return false;
        }
        byte after = content[next];
        return isSpace(after) || after == '>' || (slashEnds && after == '/');
    }

    /** The index just past the first {@code terminator} at or after {@code from} within the window, or -1. */
    private static int after(byte[] content, int from, int end, String terminator) {
        byte[] wanted = ascii(terminator);
        for (int at = from; at + wanted.length <= end; at++) {
            if (startsWith(content, at, wanted, false)) {
                return at + wanted.length;
            }
        }
        return -1;
    }

    private static int skipWhitespace(byte[] content, int from, int end) {
        int at = from;
        while (at < end && isSpace(content[at])) {
            at++;
        }
        return at;
    }

    private static boolean isSpace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n' || value == '\f';
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean startsWith(byte[] content, int at, byte[] prefix, boolean ignoreAsciiCase) {
        if (at < 0 || at + prefix.length > content.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            byte actual = content[at + i];
            if (ignoreAsciiCase && actual >= 'A' && actual <= 'Z') {
                actual = (byte) (actual + ('a' - 'A'));
            }
            if (actual != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
