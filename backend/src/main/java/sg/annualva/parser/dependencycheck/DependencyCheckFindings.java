package sg.annualva.parser.dependencycheck;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.ParsedReport;
import sg.annualva.parser.ReportText;
import sg.annualva.parser.Severity;
import sg.annualva.parser.UnparseableReportException;

/**
 * What the XML and the JSON reader of a Dependency-Check report share: it takes the dependencies as they are read, turns
 * each vulnerability into a Finding keyed {@code dc|<CVE or GHSA>|<package purl or file name>} and merges what has the same
 * key. A vulnerability is reported once per dependency, so one Finding has one occurrence per dependency (the places a
 * dependency is found are told apart by its path); the same vulnerability named twice for one dependency (by two data
 * sources) is one occurrence. The severity of an occurrence is its CVSS v3 rating, else its CVSS v2 rating, else High
 * (a vulnerability nobody rated is not assumed harmless), and a Finding's severity is the highest of its occurrences.
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
    /** The most packages one dependency may list; a real one lists a handful. */
    static final int MAX_PACKAGES = 1_000;

    private static final String PURL_PREFIX = "pkg:";
    private static final Instant EARLIEST_DATE = Instant.parse("1990-01-01T00:00:00Z");
    private static final Instant LATEST_DATE = Instant.parse("9999-12-31T00:00:00Z");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).appendOffset("+HHmm", "Z")
                    .toFormatter());

    /** One vulnerability as a dependency lists it, with the ratings it carries. */
    record Vulnerability(
            @Nullable String name, @Nullable Double cvssV3Score, @Nullable String cvssV3Label,
            @Nullable Double cvssV2Score, @Nullable String cvssV2Label) {}

    /** One Finding being merged: its places, by locator. */
    private static final class Merged {
        private final String identifier;
        private final String subject;
        private final Map<String, Severity> occurrences = new LinkedHashMap<>();

        Merged(String identifier, String subject) {
            this.identifier = identifier;
            this.subject = subject;
        }

        /** @return true when this place is new to the Finding */
        boolean occursAt(String locator, Severity severity) {
            boolean added = !occurrences.containsKey(locator);
            occurrences.merge(locator, severity, Severity::orWorse);
            return added;
        }
    }

    private final Map<String, Merged> byKey = new LinkedHashMap<>();
    private int occurrenceCount;
    private @Nullable Instant scanDate;
    private @Nullable String target;

    /** The scan date and the project name from {@code projectInfo}; either may be missing or unusable. */
    void metadata(@Nullable String projectName, @Nullable String reportDate) {
        target = ReportText.cleanAndCut(projectName, MAX_TARGET).orElse(null);
        scanDate = parseDate(reportDate);
        if (scanDate != null && (scanDate.isBefore(EARLIEST_DATE) || scanDate.isAfter(LATEST_DATE))) {
            // Not a date a scan was made on, and not one the database can hold either.
            scanDate = null;
        }
    }

    /**
     * One dependency and its vulnerabilities.
     *
     * @param packageIds the ids of its packages, in the order the report lists them; the first purl names it
     * @throws UnparseableReportException when a vulnerability cannot be keyed
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
            Merged merged = byKey.computeIfAbsent("dc|" + identifier + "|" + subject,
                    key -> new Merged(identifier, subject));
            if (merged.occursAt(locator, rate(vulnerability))) {
                occurrenceCount++;
            }
            if (byKey.size() > ParsedReport.MAX_FINDINGS || occurrenceCount > ParsedReport.MAX_OCCURRENCES) {
                throw new UnparseableReportException();
            }
        }
    }

    ParsedReport build() {
        List<ParsedReport.Finding> findings = new ArrayList<>(byKey.size());
        byKey.forEach((key, merged) -> {
            List<ParsedReport.Occurrence> places = merged.occurrences.entrySet().stream()
                    .map(place -> new ParsedReport.Occurrence(place.getKey(), place.getValue())).toList();
            Severity worst = places.stream().map(ParsedReport.Occurrence::severity).reduce(Severity::orWorse)
                    .orElse(Severity.HIGH);
            findings.add(new ParsedReport.Finding(key, merged.identifier, merged.subject, worst, places));
        });
        return new ParsedReport(new ParsedReport.Metadata(scanDate, target), List.copyOf(findings));
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

    /**
     * The instant a report date names: an ISO date and time with an offset (written +08:00, as the XML report does, or +0800, as
     * the JSON report does, or Z), or without one (taken as UTC). Anything else is no date.
     */
    static @Nullable Instant parseDate(@Nullable String raw) {
        String text = ReportText.clean(raw).orElse(null);
        if (text == null) {
            return null;
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return OffsetDateTime.parse(text, formatter).toInstant();
            } catch (DateTimeParseException notThisOne) {
                // try the next form
            }
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException notDateTime) {
            return null;
        }
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
