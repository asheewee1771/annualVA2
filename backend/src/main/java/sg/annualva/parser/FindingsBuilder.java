package sg.annualva.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every parser does with the Findings it reads, so that no vendor format repeats it: it merges what has the same
 * identity key into one Finding with one occurrence per distinct place, merges a place read twice ({@link ParsedReport.Occurrence#merge}) and takes the
 * worst severity of a Finding's places as the Finding's, and refuses a report that would make the server hold more than
 * {@link ParsedReport#MAX_FINDINGS} Findings or {@link ParsedReport#MAX_OCCURRENCES} places. The cap is checked as each
 * Finding is added, so a hostile report is refused as soon as it is over and never held whole.
 *
 * <p>A place is one that did not complete only if every reading of it did not complete: a check that failed on a host and
 * also gave an error there is a failed check. A parser builds one of these per file and is the only thing that touches it.
 */
public final class FindingsBuilder {

    /** One Finding being merged: what it is and where it was reported. */
    private static final class Merged {
        private final String identifier;
        private final String subject;
        private final Map<String, ParsedReport.Occurrence> places = new LinkedHashMap<>();

        Merged(String identifier, String subject) {
            this.identifier = identifier;
            this.subject = subject;
        }
    }

    private final Map<String, Merged> byKey = new LinkedHashMap<>();
    private int occurrenceCount;

    /**
     * Records that a Finding was reported at a place. The only place the per-file caps are enforced: a parser calls this and
     * does not count Findings or places itself.
     *
     * @param identityKey the Finding's identity within a System; the same key is the same Finding
     * @param identifier the CVE, GHSA or rule id as the report names it
     * @param subject the package or rule the identifier applies to
     * @param place where and how it was reported; a place read twice is merged by {@link ParsedReport.Occurrence#merge}
     * @throws UnparseableReportException (over a limit) when the report now holds more than a file may
     */
    public void found(String identityKey, String identifier, String subject, ParsedReport.Occurrence place)
            throws UnparseableReportException {
        Merged merged = byKey.computeIfAbsent(identityKey, key -> new Merged(identifier, subject));
        if (!merged.places.containsKey(place.locator())) {
            occurrenceCount++;
        }
        merged.places.merge(place.locator(), place, ParsedReport.Occurrence::merge);
        if (byKey.size() > ParsedReport.MAX_FINDINGS || occurrenceCount > ParsedReport.MAX_OCCURRENCES) {
            throw UnparseableReportException.overALimit();
        }
    }

    /** The merged Findings, in the order they were first found, with the metadata the parser read. */
    public ParsedReport build(ParsedReport.Metadata metadata) {
        List<ParsedReport.Finding> findings = new ArrayList<>(byKey.size());
        byKey.forEach((key, merged) -> {
            List<ParsedReport.Occurrence> places = List.copyOf(merged.places.values());
            Severity worst = places.stream().map(ParsedReport.Occurrence::severity).reduce(Severity::orWorse)
                    .orElseThrow();
            findings.add(new ParsedReport.Finding(key, merged.identifier, merged.subject, worst, places));
        });
        return new ParsedReport(metadata, List.copyOf(findings));
    }
}
