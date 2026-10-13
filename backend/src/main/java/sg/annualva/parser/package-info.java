/**
 * Reading Scan Report files. Today only the hardened XML reader the upload pipeline sniffs and checks files with
 * ({@link sg.annualva.parser.xml.SecureXml}); the parsers that turn a report into Findings arrive with their own
 * tickets, each behind one interface. Depends on nothing.
 */
package sg.annualva.parser;
