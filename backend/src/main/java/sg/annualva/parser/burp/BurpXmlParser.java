package sg.annualva.parser.burp;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.Confidence;
import sg.annualva.parser.FindingKeys;
import sg.annualva.parser.ParsedReport;
import sg.annualva.parser.ReportDates;
import sg.annualva.parser.ReportParser;
import sg.annualva.parser.ReportText;
import sg.annualva.parser.ScanReading;
import sg.annualva.parser.Severity;
import sg.annualva.parser.UnparseableReportException;
import sg.annualva.parser.xml.XmlCursor;
import sg.annualva.parser.xml.XmlCursor.Boundary;

/**
 * Reads a Burp Suite XML issue export: an {@code issues} root that holds one {@code issue} per finding of the scan. The
 * document is streamed and only what is used is read, by its full path from the root: of an {@code issue} its {@code type},
 * {@code name}, {@code host}, {@code path}, {@code location}, {@code severity} and {@code confidence}. Everything else is
 * skipped without being looked at, above all the {@code requestresponse} blocks (the requests and responses of the scan,
 * often base64, which are nearly all of a real export and may carry anything, so none of it is built, kept or said back).
 *
 * <p>Every issue is a Finding keyed {@code burp|<type id>|<host>|<normalised path>} (see {@link #normalisedPath}), so two
 * issues of one type on one page are one Finding, and each distinct {@code location} of them (the parameter or header it
 * was found in) is an occurrence of it. The severity is Burp's own (High, Medium, Low, Information, which is Informational);
 * none, or any other word, is Medium. The confidence (Certain, Firm, Tentative) is kept with the occurrence so that a person
 * can later be shown it and mark a Tentative issue False Positive; none, or any other word, is no confidence.
 *
 * <p>The scan date offered is the {@code exportTime} of the {@code issues} element, which is when the file was exported and
 * not when the scan was made (the screen says so), and the target the hosts the issues name. The DOCTYPE Burp writes is
 * never loaded or obeyed (the reader has DTDs off); it is allowed only as Burp writes it, its own internal subset of element
 * and attribute declarations, and a file whose prolog holds anything else (a DOCTYPE that declares an entity or names an
 * external id, a processing instruction) is refused. A file with another root, an issue with no type id or host, a type id or
 * host that would make two keys read alike or is too long, a path too long to key, or a value longer than
 * {@link ReportText#MAX_FIELD_CHARS} is refused; one that yields more than a file may hold is refused as too large (by
 * {@code FindingsBuilder}, the one place that counts).
 */
public final class BurpXmlParser implements ReportParser {

    private static final String ROOT = "issues";
    private static final String ISSUE = ROOT + "/issue";

    /** A Burp issue type id is a number; one this long is no id, and a key part is never cut. */
    private static final int MAX_TYPE_ID = 100;
    /** The longest normalised path a Finding may be keyed on; a longer one refuses the file, as a key is never cut. */
    private static final int MAX_PATH_KEY = 1_000;
    /** The longest issue name and location shown; they only describe the Finding or the place, so a longer one is cut. */
    private static final int MAX_NAME_SHOWN = 1_000;
    private static final int MAX_LOCATION_SHOWN = 500;
    private static final int MAX_SHORT_VALUE = 100;
    /**
     * How much of the start of the file is checked as the prolog, which must end in the root element: a real one (the XML
     * declaration and Burp's DOCTYPE) is about 3 KB, so a file whose root starts later than this is not a Burp export.
     */
    private static final int MAX_PROLOG_BYTES = 32 * 1024;

    /**
     * Everything before the root element, which must be exactly this and nothing else: an optional byte order mark, an
     * optional XML declaration, comments, an optional DOCTYPE named {@code issues} with no external id and with no internal
     * subset or one of nothing but {@code ELEMENT} and {@code ATTLIST} declarations (Burp's own), and the start of the root.
     * An {@code ENTITY} or {@code NOTATION} declaration, a processing instruction, a parameter entity reference or a second
     * DOCTYPE does not match, so none is accepted even though the reader would ignore it. It is matched against the bytes
     * and not the text the reader reports for the DOCTYPE, which is cut when the DOCTYPE is large. The loops are possessive,
     * so that no input makes the matcher recurse.
     */
    private static final Pattern PROLOG = Pattern.compile("\\A(?:\u00EF\u00BB\u00BF)?"
            + "(?:\\s*<\\?xml\\s[^?>]*+\\?>)?"
            + "(?:\\s*<!--(?:(?!--).)*+-->)*+"
            + "(?:\\s*<!DOCTYPE\\s+issues\\s*(?:\\[(?:\\s*<!(?:ELEMENT|ATTLIST)\\s[^<>%&]*+>)*+\\s*]\\s*)?>)?"
            + "(?:\\s*<!--(?:(?!--).)*+-->)*+"
            + "\\s*<issues[\\s/>]", Pattern.DOTALL);

    private static final Pattern PATH_PARAMETERS = Pattern.compile(";[^/]*");
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");

    @Override
    public ParsedReport parse(byte[] content) throws UnparseableReportException {
        String prolog = new String(content, 0, Math.min(content.length, MAX_PROLOG_BYTES), StandardCharsets.ISO_8859_1);
        if (!PROLOG.matcher(prolog).lookingAt()) {
            throw new UnparseableReportException();
        }
        return XmlCursor.read(content, BurpXmlParser::read);
    }

    private static ParsedReport read(XmlCursor xml) throws XMLStreamException, UnparseableReportException {
        ScanReading reading = new ScanReading();
        Boundary boundary;
        while ((boundary = xml.next()) != null) {
            if (boundary == Boundary.END) {
                continue;
            }
            if (xml.depth() == 1) {
                readRoot(xml, reading);
            } else if (ISSUE.equals(xml.path())) {
                readIssue(xml, reading);
            } else {
                xml.skipElement();
            }
        }
        return reading.build();
    }

    /** The root, the cursor on its start; it is left there, for its issues. */
    private static void readRoot(XmlCursor xml, ScanReading reading) throws UnparseableReportException {
        // The type check looked at the root's name only.
        if (!ROOT.equals(xml.path())) {
            throw new UnparseableReportException();
        }
        reading.facts().scanned(ReportDates.parse(xml.attribute("exportTime", MAX_SHORT_VALUE)));
        reading.sawSection();
    }

    /**
     * One issue, the cursor on its start; it is left after its end. Only what the Finding is made of is read, and the
     * requests and responses are skipped.
     */
    private static void readIssue(XmlCursor xml, ScanReading reading) throws XMLStreamException,
            UnparseableReportException {
        String issue = xml.path();
        @Nullable String type = null;
        @Nullable String name = null;
        @Nullable String host = null;
        @Nullable String path = null;
        @Nullable String location = null;
        @Nullable String severity = null;
        @Nullable String confidence = null;
        while (xml.nextChild()) {
            switch (xml.below(issue)) {
                case "/type" -> type = ReportText.firstClean(type, xml.text(MAX_TYPE_ID));
                case "/name" -> name = ReportText.firstClean(name, xml.text(ReportText.MAX_FIELD_CHARS));
                case "/host" -> host = ReportText.firstClean(host, xml.text(ReportText.MAX_FIELD_CHARS));
                case "/path" -> path = ReportText.firstClean(path, xml.text(ReportText.MAX_FIELD_CHARS));
                case "/location" -> location = ReportText.firstClean(location, xml.text(ReportText.MAX_FIELD_CHARS));
                case "/severity" -> severity = ReportText.firstClean(severity, xml.text(MAX_SHORT_VALUE));
                case "/confidence" -> confidence = ReportText.firstClean(confidence, xml.text(MAX_SHORT_VALUE));
                default -> xml.skipElement();
            }
        }
        if (type == null || host == null) {
            throw new UnparseableReportException();
        }
        String site = host.toLowerCase(Locale.ROOT);
        String page = normalisedPath(path);
        // The key is type|host|path, so a type or host with the separator in it could read as another key; neither can have one.
        if (type.indexOf('|') >= 0 || site.indexOf('|') >= 0 || !ReportText.fits(page, MAX_PATH_KEY)) {
            throw new UnparseableReportException();
        }
        // Refuses a host over its bound (a key is never cut) and keeps it for the target.
        reading.facts().named(site);
        String shown = ReportText.cleanAndCut(name, MAX_NAME_SHOWN).orElse(type);
        String place = ReportText.cleanAndCut(location, MAX_LOCATION_SHOWN).orElse(page);
        reading.findings().found(FindingKeys.burp(type, site, page), shown, site + page,
                new ParsedReport.Occurrence(place, Severity.ofBurp(severity), false, confidenceOf(confidence)));
    }

    private static @Nullable Confidence confidenceOf(@Nullable String word) {
        return word == null ? null : Confidence.ofLabel(word).orElse(null);
    }

    /**
     * The path of an issue as its Finding is keyed on it, so that the same page is the same Finding in the next scan: with
     * its query and fragment removed (a parameter's value changes from scan to scan), its path parameters ({@code ;jsessionid=...})
     * removed from every segment, repeated slashes made one, a trailing slash dropped (the root stays {@code /}), a leading slash
     * added, and the hex digits of its percent escapes in upper case. The case of the path is kept, as a server may tell
     * {@code /A} from {@code /a}; none at all is the root.
     */
    static String normalisedPath(@Nullable String raw) {
        if (raw == null) {
            return "/";
        }
        int end = raw.length();
        for (int index = 0; index < raw.length(); index++) {
            if (raw.charAt(index) == '?' || raw.charAt(index) == '#') {
                end = index;
                break;
            }
        }
        String path = PATH_PARAMETERS.matcher(raw.substring(0, end)).replaceAll("");
        path = REPEATED_SLASHES.matcher(path).replaceAll("/");
        path = PERCENT_ESCAPE.matcher(path).replaceAll(escape -> escape.group().toUpperCase(Locale.ROOT));
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
