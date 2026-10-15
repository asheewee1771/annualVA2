package sg.annualva.parser;

/**
 * The identity key of a Finding for each tool, written in one place: the prefix that tells the tools apart and the parts the key
 * is made of (the implementation plan, section 2.4). A key is matched as text when a file is uploaded again and when a Finding
 * is carried over between VA Cycles, so a change here changes which Findings are the same one. The parts are never cut by
 * these methods (a cut key could join two Findings), and a part that could make two keys read alike is the parser's to refuse.
 */
public final class FindingKeys {

    private static final char SEPARATOR = '|';

    private FindingKeys() {}

    /** {@code dc|<CVE or GHSA>|<package purl, else file name>}. */
    public static String dependencyCheck(String identifier, String subject) {
        return "dc" + SEPARATOR + identifier + SEPARATOR + subject;
    }

    /** {@code scap|<rule idref>}. */
    public static String scap(String rule) {
        return "scap" + SEPARATOR + rule;
    }

    /** {@code nessus|<check id, else check name>}. */
    public static String nessus(String check) {
        return "nessus" + SEPARATOR + check;
    }

    /** {@code burp|<issue type id>|<host>|<normalised path>}. */
    public static String burp(String typeId, String host, String path) {
        return "burp" + SEPARATOR + typeId + SEPARATOR + host + SEPARATOR + path;
    }
}
