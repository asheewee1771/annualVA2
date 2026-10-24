package sg.annualva.register;

/**
 * The Register's one rule for what a System ID and a System Name may be (ADR 0016): plain text of bounded length. The
 * preview blocks a row that breaks it; the mail allow-list (ADR 0045) refuses to send a value that breaks it, whatever
 * wrote it.
 */
public final class RegisterText {

    private static final int SYSTEM_ID_MAX = 64;
    private static final int NAME_MAX = 200;

    private RegisterText() {}

    /** A System ID: not empty, at most 64 code points, plain. */
    public static boolean systemId(String value) {
        return bounded(value, SYSTEM_ID_MAX);
    }

    /** A System Name: not empty, at most 200 code points, plain. */
    public static boolean systemName(String value) {
        return bounded(value, NAME_MAX);
    }

    /** No control, format, private-use, surrogate, unassigned or separator character other than a plain space. */
    static boolean plainCodePoint(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE,
                    Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false;
            case Character.SPACE_SEPARATOR -> codePoint == ' ';
            default -> true;
        };
    }

    private static boolean bounded(String value, int max) {
        return !value.isEmpty() && value.codePointCount(0, value.length()) <= max
                && value.codePoints().allMatch(RegisterText::plainCodePoint);
    }
}
