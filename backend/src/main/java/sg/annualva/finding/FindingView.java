package sg.annualva.finding;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.Confidence;
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
 * @param didNotComplete true for a Finding that is a check that did not complete (a SCAP result of error or unknown at every
 *     place it was reported); a failed check, and every Finding of the other Scan Reports, is false
 * @param confidence how sure the scanner says it is that the Finding is real: the most sure of the places it was reported
 *     (Burp Suite's Certain, Firm or Tentative), so a Tentative Finding is one every place of which was Tentative; null for a
 *     Finding of a Scan Report whose tool has no confidence, and for a Burp one that gave none
 * @param occurrenceCount how many places it was reported
 * @param sourceFiles the uploaded files it was read from, in the order they were uploaded; the name is the one the person
 *     gave the file
 * @param reviewState where the Cyber Manager's review of the current proposal is; null for a Finding with no proposal
 * @param currentProposal the Finding Status the SMO/Tech Lead last proposed with its fields (story 35); null until one is
 *     (earlier proposals are kept and read through the Finding's history)
 * @param source PARSED for a Finding read from a file, MANUAL for one the SMO/Tech Lead typed in (story 23), which is always
 *     shown as Manually Entered and has no place
 * @param title what the Finding is, in words, as the SMO/Tech Lead typed it for a Finding entered by hand; null for a parsed
 *     one
 * @param latestReview the Cyber Manager's latest review of this Finding, of whichever proposal it was (story 50): it is of
 *     the current proposal only when its {@code proposalId} is the current proposal's id; null until the first review
 */
public record FindingView(
        UUID id, UUID systemId, ReportType reportType, String identityKey, String identifier, String packageOrRule,
        Severity severity, boolean didNotComplete, @Nullable Confidence confidence, int occurrenceCount,
        List<SourceFile> sourceFiles, @Nullable ReviewState reviewState, @Nullable ProposalView currentProposal,
        FindingSource source, @Nullable String title, @Nullable ReviewView latestReview) {

    /** An uploaded Scan Report file a Finding was read from. */
    public record SourceFile(UUID id, String name) {}

    /** A Finding as the table holds it, with the files it was read from (by Finding; one with none has no entry). */
    static FindingView of(FindingStore.Row row, Map<UUID, List<SourceFile>> sources) {
        return new FindingView(row.id(), row.systemId(), row.reportType(), row.identityKey(), row.identifier(),
                row.packageOrRule(), row.severity(), row.didNotComplete(), row.confidence(), row.occurrenceCount(),
                sources.getOrDefault(row.id(), List.of()), row.reviewState(), row.currentProposal(), row.source(),
                row.title(), row.latestReview());
    }
}
