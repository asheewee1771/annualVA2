package sg.annualva.parser;

import java.util.Optional;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Cleans text before it is kept: the one rule for every text that comes from a person or a report and is later shown to
 * someone else. It loses every control and formatting character (a newline that would split a line, a right-to-left override
 * that would reverse what a person reads, a Unicode tag character or word joiner that hides content), every private-use and
 * unassigned code point and every lone surrogate, and is cut or refused at a fixed length. What is kept is data and is
 * rendered only as text. Text a person types (the Finding Status fields) is cleaned by the same rule, through
 * {@link #cleanLongText} and {@link #cleanOneLine}, which differ only in what becomes of a new line and a tab.
 */
public final class ReportText {

    /**
     * The most characters a parser reads for one text value of a report (a name, an identifier, a date) before it refuses the
     * file. Every value a parser keeps is far shorter; the bound is what keeps one hostile value from being built whole.
     */
    public static final int MAX_FIELD_CHARS = 4_000;

    /**
     * The longest a text a person types over several lines may be once cleaned, in code points: a Finding Status field,
     * a review comment, a return comment (ADR 0027 item 5, IM8 as-1).
     */
    public static final int MAX_LONG_TEXT = 4_000;

    private ReportText() {}

    /** The text with control and formatting characters removed and the ends trimmed; empty when nothing is left. */
    public static Optional<String> clean(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = cleaned(raw, codePoint -> -1);
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /**
     * Text a person typed that may run over several lines: cleaned by the same rule, except that new lines and tabs stay (a
     * carriage return, with every other control character, goes). Empty when nothing is left.
     */
    public static String cleanLongText(String raw) {
        return cleaned(raw, codePoint -> codePoint);
    }

    /** Text a person typed that is one line (a name, a role): a new line or a tab becomes a space. Empty when nothing is left. */
    public static String cleanOneLine(String raw) {
        return cleaned(raw, codePoint -> ' ');
    }

    /** What becomes of a new line or a tab is the argument's answer: a code point, or a negative number for nothing. */
    private static String cleaned(String raw, IntUnaryOperator lineBreak) {
        StringBuilder kept = new StringBuilder(raw.length());
        raw.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t') {
                int replacement = lineBreak.applyAsInt(codePoint);
                if (replacement >= 0) {
                    kept.appendCodePoint(replacement);
                }
            } else if (keep(codePoint)) {
                kept.appendCodePoint(codePoint);
            }
        });
        return kept.toString().strip();
    }

    /** The first of two values that says something, cleaned: the one already kept, else the next. */
    public static @Nullable String firstClean(@Nullable String kept, @Nullable String next) {
        return kept != null ? kept : clean(next).orElse(null);
    }

    /** As {@link #clean}, cut to at most this many code points; for text that is shown and never keyed on. */
    public static Optional<String> cleanAndCut(@Nullable String raw, int maxCodePoints) {
        return clean(raw).map(text -> fits(text, maxCodePoints) ? text
                : text.substring(0, text.offsetByCodePoints(0, maxCodePoints)).strip());
    }

    /** True when the text has at most this many code points. Used for text that is part of an identity key. */
    public static boolean fits(String text, int maxCodePoints) {
        return text.codePointCount(0, text.length()) <= maxCodePoints;
    }

    private static boolean keep(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type != Character.FORMAT && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR
                && type != Character.PRIVATE_USE && type != Character.UNASSIGNED && type != Character.SURROGATE;
    }
}
