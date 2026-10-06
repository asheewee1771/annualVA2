package sg.annualva.register;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns the rows of an uploaded Register file into a Register change preview (stories 2, 12 and 14): each row is
 * matched to a System by its System ID and classified ADD, UPDATE or NO_CHANGE; each System the Register holds that
 * the file leaves out is RETIRE; a row that breaks a rule is Blocked with fixed reason codes; and an SMO/Tech Lead
 * email that no sign-in has matched is flagged Unlinked SMO. It is a pure function of the file and of what the
 * Register and the user store held at the time: nothing is changed and nothing is looked up here.
 *
 * <p>Every row is checked again here whatever the template's own hints said (ADR 0016). A reason names the problem,
 * never the offending value.
 */
final class PreviewBuilder {

    static final int SYSTEM_ID_MAX = 64;
    static final int NAME_MAX = 200;
    static final int EMAIL_MAX = 254;
    private static final int REPLACEMENT = 0xFFFD;
    /** A value shown back in the preview is clipped to this many code points, so an oversized cell cannot bloat the response. */
    private static final int SHOWN_MAX = 254;
    private static final int EMAIL_LOCAL_MAX = 64;
    private static final int EMAIL_LABEL_MAX = 63;
    private static final String EXAMPLE_PREFIX = "example-";
    private static final String LOCAL_PART_SYMBOLS = ".!#$%&'*+/=?^_`{|}~-";

    private PreviewBuilder() {}

    /** A cell's text with the non-breaking spaces Excel and pasted text bring turned into spaces, then trimmed. */
    static String clean(String value) {
        return value.replace((char) 0x00A0, ' ').replace((char) 0x2007, ' ').replace((char) 0x202F, ' ').strip();
    }

    /** The lower-case emails of the SMO/Tech Leads in these rows that are well formed, to ask which have signed in. */
    static Set<String> smoEmails(List<RegisterRow> rows) {
        Set<String> emails = new HashSet<>();
        for (RegisterRow row : rows) {
            String email = lower(row.smoEmail());
            if (validEmail(email)) {
                emails.add(email);
            }
        }
        return emails;
    }

    /**
     * @param rows the file's data rows, in sheet order
     * @param existing every System the Register holds, retired or not
     * @param signedIn the lower-case SMO/Tech Lead emails of the file that match a person who has signed in
     */
    static List<PreviewRow> build(List<RegisterRow> rows, List<ExistingSystem> existing, Set<String> signedIn) {
        Map<String, ExistingSystem> byCode = new HashMap<>();
        existing.forEach(system -> byCode.put(lower(system.code()), system));
        Map<String, Integer> timesInFile = new HashMap<>();
        for (RegisterRow row : rows) {
            String key = idKey(row.systemId());
            if (key != null) {
                timesInFile.merge(key, 1, Integer::sum);
            }
        }

        List<PreviewRow> preview = new ArrayList<>();
        for (RegisterRow row : rows) {
            preview.add(classify(row, byCode, timesInFile, signedIn));
        }
        existing.stream()
                .filter(system -> !system.retired() && !timesInFile.containsKey(lower(system.code())))
                .sorted(Comparator.comparing(system -> lower(system.code())))
                .forEach(system -> preview.add(new PreviewRow(null, system.code(), system.id(), Outcome.RETIRE,
                        List.of(), false, system.snapshot(), null)));
        return preview;
    }

    private static PreviewRow classify(RegisterRow row, Map<String, ExistingSystem> byCode,
            Map<String, Integer> timesInFile, Set<String> signedIn) {
        String key = idKey(row.systemId());
        ExistingSystem match = key == null ? null : byCode.get(key);
        SystemSnapshot after = new SystemSnapshot(display(row.name()), display(lower(row.smoEmail())),
                display(lower(row.cyberManagerEmail())), display(row.quarter()), false);
        List<BlockReason> reasons = reasons(row, key, timesInFile);
        String smo = lower(row.smoEmail());
        return new PreviewRow(row.sheetRow(), row.systemId().isEmpty() ? null : display(row.systemId()),
                match == null ? null : match.id(), outcome(reasons, match, after), reasons,
                validEmail(smo) && !signedIn.contains(smo), match == null ? null : match.snapshot(), after);
    }

    /** One outcome per row: BLOCKED when it breaks a rule, otherwise what it would do to the matched System. */
    private static Outcome outcome(List<BlockReason> reasons, @Nullable ExistingSystem match, SystemSnapshot after) {
        if (!reasons.isEmpty()) {
            return Outcome.BLOCKED;
        }
        if (match == null) {
            return Outcome.ADD;
        }
        return match.snapshot().equals(after) ? Outcome.NO_CHANGE : Outcome.UPDATE;
    }

    private static List<BlockReason> reasons(RegisterRow row, @Nullable String key, Map<String, Integer> timesInFile) {
        List<BlockReason> reasons = new ArrayList<>();
        if (row.systemId().isEmpty()) {
            reasons.add(BlockReason.SYSTEM_ID_MISSING);
        } else if (key == null) {
            reasons.add(BlockReason.SYSTEM_ID_INVALID);
        } else {
            if (timesInFile.getOrDefault(key, 0) > 1) {
                reasons.add(BlockReason.SYSTEM_ID_DUPLICATE);
            }
            if (key.startsWith(EXAMPLE_PREFIX)) {
                reasons.add(BlockReason.SYSTEM_ID_EXAMPLE);
            }
        }
        if (row.name().isEmpty()) {
            reasons.add(BlockReason.SYSTEM_NAME_MISSING);
        } else if (row.name().codePointCount(0, row.name().length()) > NAME_MAX || !plain(row.name())) {
            reasons.add(BlockReason.SYSTEM_NAME_INVALID);
        }
        String smo = lower(row.smoEmail());
        String manager = lower(row.cyberManagerEmail());
        if (smo.isEmpty()) {
            reasons.add(BlockReason.SMO_EMAIL_MISSING);
        } else if (!validEmail(smo)) {
            reasons.add(BlockReason.SMO_EMAIL_INVALID);
        }
        if (manager.isEmpty()) {
            reasons.add(BlockReason.CYBER_MANAGER_MISSING);
        } else if (!validEmail(manager)) {
            reasons.add(BlockReason.CYBER_MANAGER_EMAIL_INVALID);
        } else if (manager.equals(smo)) {
            reasons.add(BlockReason.CYBER_MANAGER_IS_SMO);
        }
        if (row.quarter().isEmpty()) {
            reasons.add(BlockReason.QUARTER_MISSING);
        } else if (!RegisterTemplate.QUARTERS.contains(row.quarter())) {
            reasons.add(BlockReason.QUARTER_INVALID);
        }
        return List.copyOf(reasons);
    }

    /** The key a System ID is matched by (trimmed, without regard to case), or null when it cannot be one. */
    private static @Nullable String idKey(String systemId) {
        if (systemId.isEmpty() || systemId.codePointCount(0, systemId.length()) > SYSTEM_ID_MAX || !plain(systemId)) {
            return null;
        }
        return lower(systemId);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * A cell fit to show back and to store: at most {@link #SHOWN_MAX} code points, cut on a code-point boundary, with
     * every control, format, private-use, surrogate, unassigned or separator code point replaced by U+FFFD. The
     * validators judge the raw value, so such a row stays Blocked; only this copy leaves the class, so a NUL or a lone
     * surrogate can never reach the database, which refuses them in a JSON document and quotes the document back.
     */
    static String display(String value) {
        StringBuilder shown = new StringBuilder(Math.min(value.length(), SHOWN_MAX));
        int index = 0;
        for (int count = 0; index < value.length() && count < SHOWN_MAX; count++) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            shown.appendCodePoint(plainCodePoint(codePoint) ? codePoint : REPLACEMENT);
        }
        return shown.toString();
    }

    /** No control, format, private-use, surrogate, unassigned or separator characters other than a plain space. */
    private static boolean plain(String value) {
        return value.codePoints().allMatch(PreviewBuilder::plainCodePoint);
    }

    private static boolean plainCodePoint(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE,
                    Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false;
            case Character.SPACE_SEPARATOR -> codePoint == ' ';
            default -> true;
        };
    }

    /**
     * One address, as written for a mailbox: a local part of letters, digits and the usual symbols, an {@code @}, and a
     * domain of at least two dot-separated labels. Hand-written, with no backtracking, because the input is untrusted.
     */
    static boolean validEmail(String email) {
        if (email.isEmpty() || email.length() > EMAIL_MAX) {
            return false;
        }
        int at = email.indexOf('@');
        if (at < 1 || at != email.lastIndexOf('@')) {
            return false;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        return validLocalPart(local) && validDomain(domain);
    }

    private static boolean validLocalPart(String local) {
        if (local.length() > EMAIL_LOCAL_MAX || local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        return local.chars().allMatch(c -> isAsciiLetterOrDigit(c) || LOCAL_PART_SYMBOLS.indexOf(c) >= 0);
    }

    private static boolean validDomain(String domain) {
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > EMAIL_LABEL_MAX || label.startsWith("-") || label.endsWith("-")
                    || !label.chars().allMatch(c -> isAsciiLetterOrDigit(c) || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
