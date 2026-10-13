package sg.annualva.parser;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Cleans text read from a report before it is kept. A report is untrusted, so its text loses every control and
 * formatting character (a newline that would split a line, a right-to-left override that would reverse what a person
 * reads) and is cut or refused at a fixed length. What is kept is data and is rendered only as text.
 */
public final class ReportText {

    private ReportText() {}

    /** The text with control and formatting characters removed and the ends trimmed; empty when nothing is left. */
    public static Optional<String> clean(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        StringBuilder kept = new StringBuilder(raw.length());
        raw.codePoints().filter(ReportText::keep).forEach(kept::appendCodePoint);
        String text = kept.toString().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
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
