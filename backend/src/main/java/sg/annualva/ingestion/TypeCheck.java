package sg.annualva.ingestion;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;
import sg.annualva.finding.ReportType;
import sg.annualva.parser.xml.SecureXml;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * The type allowlist (IM8 as-1, story 21): decides from the bytes alone which format a file is, and refuses one that is
 * not allowed for the Scan Report it was uploaded to or that is over the limit for its format. For XML it reads only as
 * far as the root element; for JSON it reads the top-level keys, skipping every value without keeping it. Neither builds
 * a document or loads anything the file names. What is declared (the file name, the content type) is never consulted.
 */
final class TypeCheck {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> DEPENDENCY_CHECK_JSON_KEYS = Set.of("reportSchema", "scanInfo", "dependencies");

    /** The outcome: the format of an accepted file, or the reason it is refused. Exactly one is set. */
    record Result(@Nullable ReportFormat format, @Nullable RejectCode rejection) {

        static Result accepted(ReportFormat format) {
            return new Result(format, null);
        }

        static Result refused(RejectCode code) {
            return new Result(null, code);
        }
    }

    private TypeCheck() {}

    static Result check(ReportType reportType, FileKind kind, byte[] content) {
        if (kind != FileKind.MACHINE_READABLE) {
            // Evidence (PDF, HTML) has no allowed type until ticket 18.
            return Result.refused(RejectCode.TYPE);
        }
        Optional<ReportFormat> sniffed = sniff(content);
        if (sniffed.isEmpty() || sniffed.get().reportType() != reportType) {
            return Result.refused(RejectCode.TYPE);
        }
        ReportFormat format = sniffed.get();
        return content.length > format.maxBytes() ? Result.refused(RejectCode.TOO_LARGE) : Result.accepted(format);
    }

    /** The format the bytes are, or empty when they are none of the allowed ones (or cannot be told). */
    static Optional<ReportFormat> sniff(byte[] content) {
        int start = firstContentByte(content);
        if (start >= content.length) {
            return Optional.empty();
        }
        return switch (content[start]) {
            case '<' -> Optional.ofNullable(xmlFormat(content));
            case '{' -> jsonFormat(content) ? Optional.of(ReportFormat.DEPENDENCY_CHECK_JSON) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static int firstContentByte(byte[] content) {
        int index = 0;
        if (content.length >= UTF8_BOM.length && content[0] == UTF8_BOM[0] && content[1] == UTF8_BOM[1]
                && content[2] == UTF8_BOM[2]) {
            index = UTF8_BOM.length;
        }
        while (index < content.length && (content[index] == ' ' || content[index] == '\t' || content[index] == '\r'
                || content[index] == '\n')) {
            index++;
        }
        return index;
    }

    /** The format named by the root element's local name, or null when the root is not one of the allowed ones. */
    private static @Nullable ReportFormat xmlFormat(byte[] content) {
        try {
            XMLStreamReader reader = SecureXml.reader(content);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        return formatOfRoot(reader.getLocalName());
                    }
                }
                return null;
            } finally {
                reader.close();
            }
        } catch (XMLStreamException | RuntimeException e) {
            // A document whose root cannot be read cannot be proved to be an allowed type.
            return null;
        }
    }

    private static @Nullable ReportFormat formatOfRoot(String localName) {
        return switch (localName) {
            case "analysis" -> ReportFormat.DEPENDENCY_CHECK_XML;
            case "Benchmark", "TestResult" -> ReportFormat.XCCDF_RESULTS;
            case "asset-report-collection" -> ReportFormat.ARF;
            case "NessusClientData_v2" -> ReportFormat.NESSUS;
            case "issues" -> ReportFormat.BURP_XML;
            default -> null;
        };
    }

    /** True when the top-level object has every key a Dependency-Check JSON report has. */
    private static boolean jsonFormat(byte[] content) {
        Set<String> keys = new HashSet<>();
        try (JsonParser parser = JSON.createParser(content)) {
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    keys.add(parser.currentName());
                    parser.nextToken();
                    parser.skipChildren();
                }
            }
        } catch (RuntimeException e) {
            // Truncated or malformed after the keys it already gave: what was read decides the type, and the schema
            // check decides whether the rest can be read.
        }
        return keys.containsAll(DEPENDENCY_CHECK_JSON_KEYS);
    }
}
