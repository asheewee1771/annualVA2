package sg.annualva.parser;

/**
 * One parser per vendor format behind one interface: the bytes of a file that already passed the malware scan, the type
 * allowlist and the schema check go in, the Findings and the metadata come out. Vendor formats change independently, so
 * each has a parser of its own. A parser holds no state and reads only the bytes it is given, with no reader of its own
 * (every XML reader comes from {@link sg.annualva.parser.xml.SecureXml}).
 */
public interface ReportParser {

    /**
     * Reads a file.
     *
     * @throws UnparseableReportException when the file is not in the shape this format has
     */
    ParsedReport parse(byte[] content) throws UnparseableReportException;
}
