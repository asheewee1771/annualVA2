package sg.annualva.finding;

import org.jspecify.annotations.Nullable;

/**
 * The body of a Finding entered by hand (story 23), every field as text so that a field that cannot be read is named in
 * the answer rather than failing the whole body. {@link ManualFindings} reads and bounds them.
 *
 * @param reportType the Scan Report it belongs to, which must be marked for Findings entered by hand
 * @param title what the Finding is, in words: at most {@value ManualFindings#MAX_TITLE} characters
 * @param identifier the CVE, GHSA, rule or issue id: at most {@value ManualFindings#MAX_IDENTIFIER} characters
 * @param packageOrRule the package, rule, or host and path it applies to: at most
 *     {@value ManualFindings#MAX_PACKAGE_OR_RULE} characters
 * @param severity CRITICAL, HIGH, MEDIUM, LOW or INFORMATIONAL
 */
public record ManualFindingRequest(
        @Nullable String reportType, @Nullable String title, @Nullable String identifier,
        @Nullable String packageOrRule, @Nullable String severity) {}
