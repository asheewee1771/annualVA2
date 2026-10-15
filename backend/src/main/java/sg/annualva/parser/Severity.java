package sg.annualva.parser;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The five-level severity scale every Finding uses (the implementation plan, section 2.4), from the most to the least
 * serious. Each parser maps its tool's rating onto it. A Finding's severity is the highest of its occurrences.
 */
public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFORMATIONAL;

    /** The more serious of the two. */
    public Severity orWorse(Severity other) {
        return other.ordinal() < ordinal() ? other : this;
    }

    /**
     * The rating of a CVSS v3 base score: 9.0 and over is Critical, 7.0 High, 4.0 Medium, anything above 0 Low, and 0.0
     * (the standard's "None") Informational.
     *
     * @return empty for a score outside 0.0 to 10.0
     */
    public static Optional<Severity> ofCvssV3(double score) {
        if (!(score >= 0.0 && score <= 10.0)) {
            return Optional.empty();
        }
        if (score >= 9.0) {
            return Optional.of(CRITICAL);
        }
        if (score >= 7.0) {
            return Optional.of(HIGH);
        }
        if (score >= 4.0) {
            return Optional.of(MEDIUM);
        }
        return Optional.of(score > 0.0 ? LOW : INFORMATIONAL);
    }

    /**
     * The rating of a CVSS v2 base score. Version 2 has no Critical band: 7.0 and over is High, 4.0 Medium, anything
     * below Low.
     *
     * @return empty for a score outside 0.0 to 10.0
     */
    public static Optional<Severity> ofCvssV2(double score) {
        if (!(score >= 0.0 && score <= 10.0)) {
            return Optional.empty();
        }
        if (score >= 7.0) {
            return Optional.of(HIGH);
        }
        return Optional.of(score >= 4.0 ? MEDIUM : LOW);
    }

    /**
     * What a Finding is when its tool gave it no rating, or one this scale does not know (implementation plan, section 2.4):
     * Medium, for the configuration and web scanners. Dependency-Check is the exception, and says so itself: a vulnerability
     * nobody rated is High.
     */
    private static final Severity UNRATED = MEDIUM;

    /** The severity a SCAP rule result carries, in words; none, or a word this scale does not know, is unrated: Medium. */
    public static Severity ofScap(@Nullable String label) {
        return ofLabelOrUnrated(label);
    }

    private static Severity ofLabelOrUnrated(@Nullable String label) {
        return label == null ? UNRATED : ofLabel(label).orElse(UNRATED);
    }

    /**
     * The severity of a Nessus item: its own scale 0 (Info) to 4 (Critical), or the word for a level; none, or anything
     * else, is unrated: Medium.
     */
    public static Severity ofNessus(@Nullable String level) {
        if (level == null) {
            return UNRATED;
        }
        return switch (level.strip()) {
            case "0" -> INFORMATIONAL;
            case "1" -> LOW;
            case "2" -> MEDIUM;
            case "3" -> HIGH;
            case "4" -> CRITICAL;
            default -> ofLabelOrUnrated(level);
        };
    }

    /**
     * The severity of a Burp issue: High, Medium, Low or Information, in any case ("Information" is Informational); none, or
     * a word this scale does not know, is unrated: Medium.
     */
    public static Severity ofBurp(@Nullable String label) {
        if (label != null && "INFORMATION".equals(label.strip().toUpperCase(Locale.ROOT))) {
            return INFORMATIONAL;
        }
        return ofLabelOrUnrated(label);
    }

    /** The severity a tool names in words (CRITICAL, HIGH, MEDIUM, LOW, NONE), in any case; empty for any other word. */
    public static Optional<Severity> ofLabel(String label) {
        return switch (label.strip().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> Optional.of(CRITICAL);
            case "HIGH" -> Optional.of(HIGH);
            case "MEDIUM", "MODERATE" -> Optional.of(MEDIUM);
            case "LOW" -> Optional.of(LOW);
            case "NONE", "INFO", "INFORMATIONAL" -> Optional.of(INFORMATIONAL);
            default -> Optional.empty();
        };
    }
}
