package sg.annualva.finding;

import org.jspecify.annotations.Nullable;

/**
 * The body of a mode change, every field as text so that a field that cannot be read is named in the answer.
 *
 * @param mode UPLOAD, NOT_APPLICABLE (Dependency-Check or Burp Suite only) or MANUAL
 * @param justification required for NOT_APPLICABLE and MANUAL, not used with UPLOAD
 */
public record ReportModeRequest(@Nullable String mode, @Nullable String justification) {}
