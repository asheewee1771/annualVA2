package sg.annualva.parser.xml;

import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.UnparseableReportException;

/**
 * A forward-only walk over the elements of an untrusted XML report, on a reader from {@link SecureXml}. It is what the XML
 * parsers share, so that none of them builds a tree or reads a value without a limit: it reports each element's full path
 * from the root (so a parser matches {@code analysis/dependencies/dependency/fileName} and a look-alike element elsewhere is
 * never mistaken for it), reads a leaf's text only up to a given length (an attribute is checked against its length once the reader has it), refuses an element deeper than {@link #MAX_DEPTH}, and can skip a whole subtree
 * without looking at it. Nothing but the position is kept.
 */
public final class XmlCursor implements AutoCloseable {

    /**
     * The deepest element the cursor goes into, as deep as the schema check lets a document be (ADR 0021): a parser walking a
     * document is refused at the first element deeper than this, so the stack of open elements is bounded on its own.
     */
    public static final int MAX_DEPTH = 100;

    /** Which side of an element the cursor is on. */
    public enum Boundary {
        START,
        END
    }

    private final XMLStreamReader reader;
    private final List<String> open = new ArrayList<>();
    private String current = "";

    private XmlCursor(XMLStreamReader reader) {
        this.reader = reader;
    }

    /** A cursor before the first element of the document. */
    public static XmlCursor over(byte[] content) throws XMLStreamException {
        return new XmlCursor(SecureXml.reader(content));
    }

    /**
     * Moves to the next start or end of an element.
     *
     * @return which it is, or null at the end of the document
     * @throws UnparseableReportException when an element is deeper than {@link #MAX_DEPTH}
     */
    public @Nullable Boundary next() throws XMLStreamException, UnparseableReportException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (open.size() >= MAX_DEPTH) {
                    throw new UnparseableReportException();
                }
                current = (open.isEmpty() ? "" : open.get(open.size() - 1) + "/") + reader.getLocalName();
                open.add(current);
                return Boundary.START;
            }
            if (event == XMLStreamConstants.END_ELEMENT) {
                current = open.remove(open.size() - 1);
                return Boundary.END;
            }
        }
        return null;
    }

    /** The path from the root, by local names, of the element just started or ended: {@code analysis/dependencies}. */
    public String path() {
        return current;
    }

    /** How many elements are open, the one just started included and the one just ended not: 1 for the root. */
    public int depth() {
        return open.size();
    }

    /** The namespace of the element just started; null when it has none. */
    public @Nullable String namespace() {
        return reader.getNamespaceURI();
    }

    /**
     * An attribute of the element just started, by local name.
     *
     * @return its value, or null when it has none
     * @throws UnparseableReportException when it is longer than this many characters
     */
    public @Nullable String attribute(String name, int maxChars) throws UnparseableReportException {
        String value = reader.getAttributeValue(null, name);
        if (value != null && value.length() > maxChars) {
            throw new UnparseableReportException();
        }
        return value;
    }

    /**
     * The text of the leaf element just started, which it ends: the cursor is then after the element, and its end is not
     * reported. The text is read in pieces and refused as soon as it is longer than the limit, so a huge value is never built.
     *
     * @throws UnparseableReportException when the text is longer than this many characters or the element holds markup
     */
    public String text(int maxChars) throws UnparseableReportException, XMLStreamException {
        StringBuilder text = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE,
                        XMLStreamConstants.ENTITY_REFERENCE -> {
                    if (text.length() + reader.getTextLength() > maxChars) {
                        throw new UnparseableReportException();
                    }
                    text.append(reader.getText());
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    current = open.remove(open.size() - 1);
                    return text.toString();
                }
                case XMLStreamConstants.START_ELEMENT -> throw new UnparseableReportException();
                default -> {
                    // A comment or a processing instruction holds no text of the element.
                }
            }
        }
        throw new XMLStreamException("The document ended inside an element");
    }

    /**
     * Skips the element just started and everything in it, without building a path for any of it.
     *
     * @throws UnparseableReportException when something in it is deeper than {@link #MAX_DEPTH} from the root
     */
    public void skipElement() throws XMLStreamException, UnparseableReportException {
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (open.size() - 1 + depth > MAX_DEPTH) {
                    throw new UnparseableReportException();
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        current = open.remove(open.size() - 1);
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (XMLStreamException e) {
            // Nothing is held that a failed close could leak, and its message may quote the file.
        }
    }
}
