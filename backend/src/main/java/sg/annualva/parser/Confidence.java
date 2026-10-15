package sg.annualva.parser;

import java.util.Locale;
import java.util.Optional;

/**
 * How sure a scanner says it is that an issue is real, from the most to the least sure: Burp Suite's Certain, Firm and
 * Tentative. It is kept beside the severity, so that a later ticket (30) can show it and offer a Tentative issue for False
 * Positive with a reason. Only Burp rates its issues this way; every other Finding has none.
 */
public enum Confidence {
    CERTAIN,
    FIRM,
    TENTATIVE;

    /** The more sure of the two. */
    public Confidence orMoreSure(Confidence other) {
        return other.ordinal() < ordinal() ? other : this;
    }

    /** The confidence a tool names in words, in any case; empty for any other word. */
    public static Optional<Confidence> ofLabel(String label) {
        return switch (label.strip().toUpperCase(Locale.ROOT)) {
            case "CERTAIN" -> Optional.of(CERTAIN);
            case "FIRM" -> Optional.of(FIRM);
            case "TENTATIVE" -> Optional.of(TENTATIVE);
            default -> Optional.empty();
        };
    }
}
