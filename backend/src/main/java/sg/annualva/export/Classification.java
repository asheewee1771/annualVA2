package sg.annualva.export;

/**
 * The classification an export carries in its header, footer and first row (story 86, assumption A-1): Confidential when
 * it covers the complete set (every System of the Quarter or the Cycle it is about), otherwise Restricted /
 * Sensitive-Normal.
 */
public enum Classification {
    CONFIDENTIAL("Confidential"),
    RESTRICTED("Restricted / Sensitive-Normal");

    private final String label;

    Classification(String label) {
        this.label = label;
    }

    /** The words the file carries. */
    String label() {
        return label;
    }
}
