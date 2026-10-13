/**
 * Reading Scan Report files. The hardened XML reader the upload pipeline sniffs and checks files with ({@link
 * sg.annualva.parser.xml.SecureXml}) and the parsers that turn a report into Findings, one per vendor format behind one
 * interface ({@link sg.annualva.parser.ReportParser}): the Dependency-Check one is in {@code parser.dependencycheck}, the
 * others arrive with their own tickets. It also holds the five-level {@link sg.annualva.parser.Severity} scale every
 * parser maps onto. Depends on nothing.
 */
package sg.annualva.parser;
