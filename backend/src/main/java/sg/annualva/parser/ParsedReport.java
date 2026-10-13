package sg.annualva.parser;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a parser read out of one Scan Report file: the metadata the SMO/Tech Lead confirms and the Findings, already
 * merged so that each identity key appears once per file. Every string is data from an untrusted file, cleaned of control
 * characters and cut to a fixed length; it is stored and returned as text and never reaches a log line, an audit entry or an
 * error body.
 *
 * @param metadata what the file says about the scan itself
 * @param findings the Findings read from the file, each with the places it was reported
 */
public record ParsedReport(Metadata metadata, List<Finding> findings) {

    /**
     * The most Findings one file may yield, and the most places (occurrences) they may be reported at. A report over either is
     * refused as not in the shape of its format, because a parser holds every Finding of a file in memory and the file is
     * untrusted: a few tiny vulnerabilities per kilobyte would otherwise make a 25 MB file hundreds of thousands of rows.
     * A real report names a few thousand; the cap is a proposal a human confirms (ADR 0022).
     */
    public static final int MAX_FINDINGS = 20_000;

    /** See {@link #MAX_FINDINGS}. */
    public static final int MAX_OCCURRENCES = 50_000;

    /**
     * @param scanDate when the file says the scan was made; null when it says nothing usable
     * @param target the project, host or application the file says it scanned, offered as the target; null when it names none
     */
    public record Metadata(@Nullable Instant scanDate, @Nullable String target) {}

    /**
     * One Finding of the file.
     *
     * @param identityKey the Finding's identity within a System, such as {@code dc|CVE-2021-44228|pkg:maven/...}; the same
     *     key is used to match re-uploads and to carry Findings over between VA Cycles
     * @param identifier the CVE, GHSA or rule id as the report names it
     * @param subject the package or rule the identifier applies to, as the key names it
     * @param severity the highest severity of its occurrences
     * @param occurrences each place it was reported, one per distinct locator
     */
    public record Finding(
            String identityKey, String identifier, String subject, Severity severity, List<Occurrence> occurrences) {}

    /**
     * One place a Finding was reported.
     *
     * @param locator the component or source file as the report names it
     * @param severity the severity at this place
     */
    public record Occurrence(String locator, Severity severity) {}
}
