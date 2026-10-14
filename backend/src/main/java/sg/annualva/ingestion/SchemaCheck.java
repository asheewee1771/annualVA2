package sg.annualva.ingestion;

import java.util.Optional;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import sg.annualva.parser.xml.SecureXml;
import sg.annualva.parser.xml.XmlCursor;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schema check (IM8 as-1, story 24): reads the whole file once, without keeping it, and refuses one that cannot be
 * read to the end (a truncated or malformed document) or whose structure is not the one its format needs. It is the last
 * check before a file is handed to a parser, which then reads the Findings out of it with checks of its own (tickets 14
 * to 17), so it asks only for what every file of the format has: a well-formed document, an XML one nested no deeper
 * than {@value #MAX_DEPTH} levels (the JSON reader enforces its own, deeper limit and refuses what exceeds it as
 * unreadable), and for Dependency-Check JSON a {@code dependencies} array.
 */
final class SchemaCheck {

    /** Deeper than any real report; a document nested further is an attack on a reader's stack, not a report. */
    static final int MAX_DEPTH = XmlCursor.MAX_DEPTH;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SchemaCheck() {}

    /** @return the reason the file is refused, or empty when it passes */
    static Optional<RejectCode> check(ReportFormat format, byte[] content) {
        return format == ReportFormat.DEPENDENCY_CHECK_JSON ? json(content) : xml(content);
    }

    private static Optional<RejectCode> xml(byte[] content) {
        try {
            XMLStreamReader reader = SecureXml.reader(content);
            try {
                int depth = 0;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT && ++depth > MAX_DEPTH) {
                        return Optional.of(RejectCode.SCHEMA);
                    }
                    if (event == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                    }
                }
                return Optional.empty();
            } finally {
                reader.close();
            }
        } catch (XMLStreamException | RuntimeException e) {
            // The parser's message may quote the file; it is dropped here.
            return Optional.of(RejectCode.UNREADABLE);
        }
    }

    private static Optional<RejectCode> json(byte[] content) {
        boolean dependenciesAreAList = true;
        try (JsonParser parser = JSON.createParser(content)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return Optional.of(RejectCode.UNREADABLE);
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("dependencies".equals(name) && value != JsonToken.START_ARRAY) {
                    dependenciesAreAList = false;
                }
                parser.skipChildren();
            }
            if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                return Optional.of(RejectCode.UNREADABLE);
            }
        } catch (RuntimeException e) {
            return Optional.of(RejectCode.UNREADABLE);
        }
        return dependenciesAreAList ? Optional.empty() : Optional.of(RejectCode.SCHEMA);
    }
}
