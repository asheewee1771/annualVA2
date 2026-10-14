package sg.annualva.parser.nessus;

import java.util.Locale;
import javax.xml.stream.XMLStreamException;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.FindingsBuilder;
import sg.annualva.parser.ParsedReport;
import sg.annualva.parser.ReportDates;
import sg.annualva.parser.ReportParser;
import sg.annualva.parser.ReportText;
import sg.annualva.parser.ScanFacts;
import sg.annualva.parser.Severity;
import sg.annualva.parser.UnparseableReportException;
import sg.annualva.parser.xml.XmlCursor;
import sg.annualva.parser.xml.XmlCursor.Boundary;

/**
 * Reads a Nessus compliance audit: a {@code NessusClientData_v2} document whose {@code Report} holds one {@code ReportHost}
 * per scanned host, each with its {@code HostProperties} and one {@code ReportItem} per result. The document is streamed and
 * only what is used is read, by its full path from the root; everything else (the {@code Policy}, every item's description and
 * plugin output, which are most of the file) is skipped without being looked at.
 *
 * <p>A {@code ReportItem} is a compliance check only if it has a {@code compliance-result} (one of the {@code cm:} elements of a
 * compliance audit); every other item, a vulnerability or an information plugin, is ignored. A result of {@code FAILED} or
 * {@code WARNING} is a Finding keyed {@code nessus|<check id>}, or {@code nessus|<check name>} when the item has no id;
 * {@code ERROR} is a Finding too, marked as a check that did not complete, because it is neither a pass nor a fail;
 * {@code PASSED} and any other result is ignored. A Finding has one occurrence per host (the {@code name} of its
 * {@code ReportHost}). The severity is the item's {@code severity} attribute (0 Info, 1 Low, 2 Medium, 3 High, 4 Critical, or
 * the word for it); none, or anything else, is Medium.
 *
 * <p>The scan date offered is the latest {@code HOST_END} of the file's hosts and the target the hosts it names. A file that
 * is not a {@code NessusClientData_v2} with a {@code Report}, has a kept result with no check id or name or in a host with no
 * name, or has a value longer than {@link ReportText#MAX_FIELD_CHARS} is refused; one that yields more than a file may hold is
 * refused as too large (by {@link FindingsBuilder}, the one place that counts).
 */
public final class NessusXmlParser implements ReportParser {

    private static final String ROOT = "NessusClientData_v2";
    private static final String REPORT = ROOT + "/Report";
    private static final String HOST = REPORT + "/ReportHost";

    /**
     * The longest check id or name a Finding may be keyed on, in characters. A real check name is a few hundred; a key is
     * never cut (two long names sharing a start would become one Finding), so a longer one refuses the file.
     */
    private static final int MAX_CHECK_KEY = 1_000;
    /** The longest check name shown for a Finding; the name only describes it, so a longer one is cut and not refused. */
    private static final int MAX_CHECK_SHOWN = 1_000;
    private static final int MAX_SHORT_VALUE = 100;

    /** What the whole file has said so far. */
    private static final class Reading {
        private final FindingsBuilder findings = new FindingsBuilder();
        private final ScanFacts facts = new ScanFacts();
        private int reports;
    }

    @Override
    public ParsedReport parse(byte[] content) throws UnparseableReportException {
        try (XmlCursor xml = XmlCursor.over(content)) {
            return read(xml);
        } catch (XMLStreamException | RuntimeException e) {
            // The reader's message may quote the file; it is dropped here.
            throw new UnparseableReportException();
        }
    }

    private static ParsedReport read(XmlCursor xml) throws XMLStreamException, UnparseableReportException {
        Reading reading = new Reading();
        Boundary boundary;
        while ((boundary = xml.next()) != null) {
            if (boundary == Boundary.END) {
                continue;
            }
            String path = xml.path();
            if (HOST.equals(path)) {
                readHost(xml, reading);
            } else if (REPORT.equals(path)) {
                reading.reports++;
            } else if (!ROOT.equals(path)) {
                xml.skipElement();
            }
        }
        if (reading.reports == 0) {
            // Not an export of a scan (the type check looked at the root's name only): the results are in a Report, under the
            // root, so a document with another root or no Report is refused here.
            throw new UnparseableReportException();
        }
        return reading.findings.build(reading.facts.metadata());
    }

    /** One host, the cursor on its start; it is left after its end. Its name is the place of every result in it. */
    private static void readHost(XmlCursor xml, Reading reading) throws XMLStreamException, UnparseableReportException {
        String host = xml.path();
        String name = ReportText.clean(xml.attribute("name", ReportText.MAX_FIELD_CHARS)).orElse(null);
        if (name != null) {
            reading.facts.named(name);
        }
        Boundary boundary;
        while ((boundary = xml.next()) == Boundary.START) {
            switch (xml.path().substring(host.length())) {
                case "/HostProperties" -> readHostProperties(xml, reading.facts);
                case "/ReportItem" -> readItem(xml, name, reading.findings);
                default -> xml.skipElement();
            }
        }
        if (boundary == null) {
            throw new XMLStreamException("The document ended inside a ReportHost");
        }
    }

    /** A host's properties, the cursor on their start; it is left after their end. Only the time the host was done is read. */
    private static void readHostProperties(XmlCursor xml, ScanFacts facts) throws XMLStreamException,
            UnparseableReportException {
        String properties = xml.path();
        Boundary boundary;
        while ((boundary = xml.next()) == Boundary.START) {
            if (xml.path().equals(properties + "/tag") && "HOST_END".equals(xml.attribute("name", MAX_SHORT_VALUE))) {
                facts.scanned(ReportDates.parse(xml.text(MAX_SHORT_VALUE)));
            } else {
                xml.skipElement();
            }
        }
        if (boundary == null) {
            throw new XMLStreamException("The document ended inside HostProperties");
        }
    }

    /**
     * One result of a host, the cursor on its start; it is left after its end. Only a failed, warned or errored compliance
     * check is kept, and only its id, name and result are read.
     */
    private static void readItem(XmlCursor xml, @Nullable String host, FindingsBuilder findings)
            throws XMLStreamException, UnparseableReportException {
        String item = xml.path();
        @Nullable String severity = xml.attribute("severity", MAX_SHORT_VALUE);
        @Nullable String checkId = null;
        @Nullable String checkName = null;
        @Nullable String result = null;
        Boundary boundary;
        while ((boundary = xml.next()) == Boundary.START) {
            switch (xml.path().substring(item.length())) {
                case "/compliance-check-id" ->
                    checkId = ReportText.firstClean(checkId, xml.text(ReportText.MAX_FIELD_CHARS));
                case "/compliance-check-name" ->
                    checkName = ReportText.firstClean(checkName, xml.text(ReportText.MAX_FIELD_CHARS));
                case "/compliance-result" -> result = ReportText.firstClean(result, xml.text(MAX_SHORT_VALUE));
                default -> xml.skipElement();
            }
        }
        if (boundary == null) {
            throw new XMLStreamException("The document ended inside a ReportItem");
        }
        if (result == null) {
            return;
        }
        String verdict = result.toUpperCase(Locale.ROOT);
        boolean didNotComplete = "ERROR".equals(verdict);
        if (!didNotComplete && !"FAILED".equals(verdict) && !"WARNING".equals(verdict)) {
            return;
        }
        String key = checkId != null ? checkId : checkName;
        if (key == null || host == null || !ReportText.fits(key, MAX_CHECK_KEY)) {
            throw new UnparseableReportException();
        }
        String shown = ReportText.cleanAndCut(checkName != null ? checkName : checkId, MAX_CHECK_SHOWN).orElse(key);
        findings.found("nessus|" + key, shown, shown,
                new ParsedReport.Occurrence(host, severityOf(severity), didNotComplete));
    }

    /** Nessus's own scale is 0 to 4; a word for a level is read too; none, or anything else, is unrated, and Medium. */
    private static Severity severityOf(@Nullable String attribute) {
        if (attribute == null) {
            return Severity.MEDIUM;
        }
        return switch (attribute.strip()) {
            case "0" -> Severity.INFORMATIONAL;
            case "1" -> Severity.LOW;
            case "2" -> Severity.MEDIUM;
            case "3" -> Severity.HIGH;
            case "4" -> Severity.CRITICAL;
            default -> Severity.ofLabel(attribute).orElse(Severity.MEDIUM);
        };
    }
}
