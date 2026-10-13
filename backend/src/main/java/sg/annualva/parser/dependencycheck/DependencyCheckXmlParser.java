package sg.annualva.parser.dependencycheck;

import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.ParsedReport;
import sg.annualva.parser.ReportParser;
import sg.annualva.parser.UnparseableReportException;
import sg.annualva.parser.xml.SecureXml;

/**
 * Reads an OWASP Dependency-Check XML report: an {@code analysis} root in the DependencyCheck namespace holding
 * {@code projectInfo} (the project's name and the report date) and {@code dependencies}. The document is streamed, never
 * built, and only the elements that matter are read, by their full path from the root, so a {@code relatedDependencies}
 * or {@code suppressedVulnerabilities} block, whose entries look like the real thing, is never mistaken for one. Anything
 * that is not that shape is refused (a different root or namespace, no {@code dependencies}, an element holding markup
 * where text belongs). The reader comes from {@link SecureXml}: no DTD, no external entity.
 */
public final class DependencyCheckXmlParser implements ReportParser {

    private static final String NAMESPACE_START = "https://jeremylong.github.io/DependencyCheck/dependency-check.";
    private static final String NAMESPACE_END = ".xsd";

    private static final String ROOT = "analysis";
    private static final String PROJECT_NAME = ROOT + "/projectInfo/name";
    private static final String REPORT_DATE = ROOT + "/projectInfo/reportDate";
    private static final String DEPENDENCIES = ROOT + "/dependencies";
    private static final String DEPENDENCY = DEPENDENCIES + "/dependency";
    private static final String FILE_NAME = DEPENDENCY + "/fileName";
    private static final String FILE_PATH = DEPENDENCY + "/filePath";
    private static final String PACKAGE_ID = DEPENDENCY + "/identifiers/package/id";
    private static final String VULNERABILITY = DEPENDENCY + "/vulnerabilities/vulnerability";
    private static final String VULNERABILITY_NAME = VULNERABILITY + "/name";
    private static final String CVSS_V3_SCORE = VULNERABILITY + "/cvssV3/baseScore";
    private static final String CVSS_V3_LABEL = VULNERABILITY + "/cvssV3/baseSeverity";
    private static final String CVSS_V2_SCORE = VULNERABILITY + "/cvssV2/score";
    private static final String CVSS_V2_LABEL = VULNERABILITY + "/cvssV2/severity";

    /** The dependency being read. */
    private static final class Dependency {
        @Nullable String fileName;
        @Nullable String filePath;
        final List<String> packageIds = new ArrayList<>();
        final List<DependencyCheckFindings.Vulnerability> vulnerabilities = new ArrayList<>();
    }

    /** The vulnerability being read. */
    private static final class Vulnerability {
        @Nullable String name;
        @Nullable String v3Score;
        @Nullable String v3Label;
        @Nullable String v2Score;
        @Nullable String v2Label;

        DependencyCheckFindings.Vulnerability finish() {
            return new DependencyCheckFindings.Vulnerability(name, DependencyCheckFindings.score(v3Score), v3Label,
                    DependencyCheckFindings.score(v2Score), v2Label);
        }
    }

    @Override
    public ParsedReport parse(byte[] content) throws UnparseableReportException {
        try {
            XMLStreamReader reader = SecureXml.reader(content);
            try {
                return read(reader);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException | RuntimeException e) {
            // The reader's message may quote the file; it is dropped here.
            throw new UnparseableReportException();
        }
    }

    private static ParsedReport read(XMLStreamReader reader) throws XMLStreamException, UnparseableReportException {
        DependencyCheckFindings findings = new DependencyCheckFindings();
        List<String> path = new ArrayList<>();
        @Nullable String projectName = null;
        @Nullable String reportDate = null;
        boolean sawDependencies = false;
        @Nullable Dependency dependency = null;
        @Nullable Vulnerability vulnerability = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                String ended = path.remove(path.size() - 1);
                if (ended.equals(VULNERABILITY) && dependency != null && vulnerability != null) {
                    dependency.vulnerabilities.add(vulnerability.finish());
                    if (dependency.vulnerabilities.size() > ParsedReport.MAX_FINDINGS) {
                        throw new UnparseableReportException();
                    }
                    vulnerability = null;
                } else if (ended.equals(DEPENDENCY) && dependency != null) {
                    findings.dependency(dependency.fileName, dependency.filePath, dependency.packageIds,
                            dependency.vulnerabilities);
                    dependency = null;
                }
                continue;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String here = (path.isEmpty() ? "" : path.get(path.size() - 1) + "/") + reader.getLocalName();
            if (path.isEmpty()) {
                requireDependencyCheckRoot(reader);
            }
            path.add(here);
            switch (here) {
                case DEPENDENCIES -> sawDependencies = true;
                case DEPENDENCY -> dependency = new Dependency();
                case VULNERABILITY -> vulnerability = dependency == null ? null : new Vulnerability();
                case PROJECT_NAME -> projectName = text(reader, path);
                case REPORT_DATE -> reportDate = text(reader, path);
                case FILE_NAME -> {
                    String name = text(reader, path);
                    if (dependency != null) {
                        dependency.fileName = name;
                    }
                }
                case FILE_PATH -> {
                    String filePath = text(reader, path);
                    if (dependency != null) {
                        dependency.filePath = filePath;
                    }
                }
                case PACKAGE_ID -> {
                    String id = text(reader, path);
                    if (dependency != null) {
                        dependency.packageIds.add(id);
                        if (dependency.packageIds.size() > DependencyCheckFindings.MAX_PACKAGES) {
                            throw new UnparseableReportException();
                        }
                    }
                }
                case VULNERABILITY_NAME -> {
                    String name = text(reader, path);
                    if (vulnerability != null) {
                        vulnerability.name = name;
                    }
                }
                case CVSS_V3_SCORE -> {
                    String score = text(reader, path);
                    if (vulnerability != null) {
                        vulnerability.v3Score = score;
                    }
                }
                case CVSS_V3_LABEL -> {
                    String label = text(reader, path);
                    if (vulnerability != null) {
                        vulnerability.v3Label = label;
                    }
                }
                case CVSS_V2_SCORE -> {
                    String score = text(reader, path);
                    if (vulnerability != null) {
                        vulnerability.v2Score = score;
                    }
                }
                case CVSS_V2_LABEL -> {
                    String label = text(reader, path);
                    if (vulnerability != null) {
                        vulnerability.v2Label = label;
                    }
                }
                default -> {
                    // Everything else (evidence, descriptions, references, related and suppressed entries) is not read.
                }
            }
        }
        if (!sawDependencies) {
            throw new UnparseableReportException();
        }
        findings.metadata(projectName, reportDate);
        return findings.build();
    }

    /** The root must be {@code analysis} in a DependencyCheck namespace; the type check looked at the name only. */
    private static void requireDependencyCheckRoot(XMLStreamReader reader) throws UnparseableReportException {
        String namespace = reader.getNamespaceURI();
        if (!ROOT.equals(reader.getLocalName()) || namespace == null || !namespace.startsWith(NAMESPACE_START)
                || !namespace.endsWith(NAMESPACE_END)) {
            throw new UnparseableReportException();
        }
    }

    /** The text of a leaf element, which ends the element: it is taken off the path as well. */
    private static String text(XMLStreamReader reader, List<String> path) throws XMLStreamException {
        String text = reader.getElementText();
        path.remove(path.size() - 1);
        return text;
    }
}
