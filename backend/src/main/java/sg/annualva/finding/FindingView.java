package sg.annualva.finding;

import java.util.List;
import java.util.UUID;
import sg.annualva.parser.Severity;

/**
 * A Finding as the list reads it (story 28). Every text in it was read from a report: it is data, rendered only as text,
 * and never logged or audited.
 *
 * @param id the Finding's own id
 * @param systemId the System the Finding belongs to: a Finding is scoped to its System, so a later VA Cycle can match it
 * @param reportType the Scan Report it was read from
 * @param identityKey the Finding's identity within the System, such as {@code dc|CVE-2021-44228|pkg:maven/...}: what a re-upload
 *     is matched on and what a later VA Cycle carries a status over by
 * @param identifier the CVE, GHSA or rule id as the report names it
 * @param packageOrRule the package (a purl, else the dependency's file name) or the rule it applies to
 * @param severity the highest severity of its occurrences
 * @param occurrenceCount how many places it was reported
 * @param sourceFiles the uploaded files it was read from, in the order they were uploaded; the name is the one the person
 *     gave the file
 */
public record FindingView(
        UUID id, UUID systemId, ReportType reportType, String identityKey, String identifier, String packageOrRule,
        Severity severity, int occurrenceCount, List<SourceFile> sourceFiles) {

    /** An uploaded Scan Report file a Finding was read from. */
    public record SourceFile(UUID id, String name) {}
}
