package sg.annualva.parser.dependencycheck;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.FindingsBuilder;
import sg.annualva.parser.ParsedReport;
import sg.annualva.parser.ReportDates;
import sg.annualva.parser.ReportText;
import sg.annualva.parser.Severity;
import sg.annualva.parser.UnparseableReportException;

/**
 * What the XML and the JSON reader of a Dependency-Check report share: it takes the dependencies as they are read, turns
 * each vulnerability into a Finding keyed {@code dc|<CVE or GHSA>|<package purl or file name>} and hands it to the shared
 * {@link FindingsBuilder}, which merges what has the same key. A vulnerability is reported once per dependency, so one
 * Finding has one occurrence per dependency (the places a dependency is found are told apart by its path); the same
 * vulnerability named twice for one dependency (by two data sources) is one occurrence. The severity of an occurrence is its
 * CVSS v3 rating, else its CVSS v2 rating, else High (a vulnerability nobody rated is not assumed harmless), and a Finding's
 * severity is the highest of its occurrences.
 *
 * <p>A text that is part of a key is refused when it is empty or too long rather than cut, because a cut key could
 * silently join two Findings; a vulnerability with no name, or a dependency with a vulnerability and no name of its own,
 * cannot be keyed and the whole file is refused.
 */
final class DependencyCheckFindings {

    static final int MAX_IDENTIFIER = 100;
    static final int MAX_SUBJECT = 500;
    static final int MAX_LOCATOR = 500;
    static final int MAX_TARGET = 200;
    /**
     * The per-dependency bounds, the only ones a Dependency-Check parser counts itself (the per-file caps on Findings and
     * places belong to {@link FindingsBuilder}). A dependency's entries are held until it ends, because its name may come
     * after them. A real dependency lists a handful of packages.
     */
    private static final int MAX_PACKAGES = 1_000;
    private static final int MAX_VULNERABILITIES = 20_000;

    private static final String PURL_PREFIX = "pkg:";

    /** One vulnerability as a dependency lists it, with the ratings it carries. */
    record Vulnerability(
            @Nullable String name, @Nullable Double cvssV3Score, @Nullable String cvssV3Label,
            @Nullable Double cvssV2Score, @Nullable String cvssV2Label) {}

    private final FindingsBuilder builder = new FindingsBuilder();
    private @Nullable Instant scanDate;
    private @Nullable String target;

    /** The scan date and the project name from {@code projectInfo}; either may be missing or unusable. */
    void metadata(@Nullable String projectName, @Nullable String reportDate) {
        target = ReportText.cleanAndCut(projectName, MAX_TARGET).orElse(null);
        scanDate = ReportDates.parse(reportDate);
    }

    /** Adds a package id to the dependency being read. */
    static void addPackage(List<String> packageIds, String id) throws UnparseableReportException {
        addBounded(packageIds, id, MAX_PACKAGES);
    }

    /** Adds a vulnerability to the dependency being read. */
    static void addVulnerability(List<Vulnerability> vulnerabilities, Vulnerability vulnerability)
            throws UnparseableReportException {
        addBounded(vulnerabilities, vulnerability, MAX_VULNERABILITIES);
    }

    private static <T> void addBounded(List<T> into, T item, int most) throws UnparseableReportException {
        if (into.size() >= most) {
            throw UnparseableReportException.overALimit();
        }
        into.add(item);
    }

    /**
     * One dependency and its vulnerabilities.
     *
     * @param packageIds the ids of its packages, in the order the report lists them; the first purl names it
     * @throws UnparseableReportException when a vulnerability cannot be keyed, or the report holds more than a file may
     */
    void dependency(@Nullable String fileName, @Nullable String filePath, List<String> packageIds,
            List<Vulnerability> vulnerabilities) throws UnparseableReportException {
        if (vulnerabilities.isEmpty()) {
            return;
        }
        String name = ReportText.clean(fileName).orElse(null);
        String path = ReportText.clean(filePath).orElse(null);
        String subject = packageIds.stream().map(id -> ReportText.clean(id).orElse(""))
                .filter(id -> id.startsWith(PURL_PREFIX)).findFirst().orElse(name != null ? name : path);
        if (subject == null || !ReportText.fits(subject, MAX_SUBJECT)) {
            throw new UnparseableReportException();
        }
        String locator = path != null ? path : (name != null ? name : subject);
        if (!ReportText.fits(locator, MAX_LOCATOR)) {
            throw new UnparseableReportException();
        }
        for (Vulnerability vulnerability : vulnerabilities) {
            String identifier = ReportText.clean(vulnerability.name()).orElseThrow(UnparseableReportException::new);
            if (identifier.indexOf('|') >= 0 || !ReportText.fits(identifier, MAX_IDENTIFIER)) {
                throw new UnparseableReportException();
            }
            builder.found("dc|" + identifier + "|" + subject, identifier, subject,
                    new ParsedReport.Occurrence(locator, rate(vulnerability), false));
        }
    }

    ParsedReport build() {
        return builder.build(new ParsedReport.Metadata(scanDate, target));
    }

    /** CVSS v3, then v2, by score and failing that by the rating the tool wrote; High when neither rates it. */
    static Severity rate(Vulnerability vulnerability) {
        Severity v3 = rated(vulnerability.cvssV3Score(), vulnerability.cvssV3Label(), true);
        if (v3 != null) {
            return v3;
        }
        Severity v2 = rated(vulnerability.cvssV2Score(), vulnerability.cvssV2Label(), false);
        return v2 != null ? v2 : Severity.HIGH;
    }

    private static @Nullable Severity rated(@Nullable Double score, @Nullable String label, boolean version3) {
        if (score != null) {
            var bySeverity = version3 ? Severity.ofCvssV3(score) : Severity.ofCvssV2(score);
            if (bySeverity.isPresent()) {
                return bySeverity.get();
            }
        }
        return label == null ? null : Severity.ofLabel(label).orElse(null);
    }

    /** A score written as a number; null for anything else, so a malformed score never decides a rating. */
    static @Nullable Double score(@Nullable String text) {
        if (text == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.strip());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
