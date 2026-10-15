/**
 * Reading Scan Report files. The hardened XML reader the upload pipeline sniffs and checks files with ({@link
 * sg.annualva.parser.xml.SecureXml}), the cursor the XML parsers walk a document with ({@link
 * sg.annualva.parser.xml.XmlCursor}) and the parsers that turn a report into Findings, one per vendor format behind one
 * interface ({@link sg.annualva.parser.ReportParser}): the Dependency-Check ones are in {@code parser.dependencycheck}, the SCAP
 * one in {@code parser.scap}, the Nessus one in {@code parser.nessus} and the Burp Suite one in {@code parser.burp}. What every
 * parser shares is here: the five-level {@link sg.annualva.parser.Severity} scale they map onto (and what an unrated Finding
 * is), the {@link sg.annualva.parser.Confidence} Burp adds, {@link sg.annualva.parser.FindingKeys} (the identity key of each
 * tool), {@link sg.annualva.parser.FindingsBuilder} (merging and the caps), {@link sg.annualva.parser.ScanReading} (what a
 * parser holds while it reads), {@link sg.annualva.parser.ReportText} (cleaning), {@link sg.annualva.parser.ReportDates} and
 * {@link sg.annualva.parser.ScanFacts} (the scan date and the hosts). Depends on nothing.
 */
package sg.annualva.parser;
