package sg.annualva.finding;

/**
 * The disposition the SMO/Tech Lead proposes for a Finding (CONTEXT.md, "Finding Status"). Each needs its own field,
 * which {@link ProposalFields} enforces and the table checks again. Risk Accepted expires at the end of each VA Cycle,
 * a rule a later ticket owns.
 */
public enum FindingStatus {
    /** Fixed: the evidence says how it is known to be. */
    REMEDIATED("Remediated"),
    /** Not real: the justification says why. */
    FALSE_POSITIVE("False Positive"),
    /** Still open and accepted: the reason, and who accepted it. */
    RISK_ACCEPTED("Risk Accepted"),
    /** Still open and planned: the plan and a future target date. */
    REMEDIATION_PLANNED("Remediation Planned");

    private final String label;

    FindingStatus(String label) {
        this.label = label;
    }

    /** The words the glossary and the screens use for it. */
    String label() {
        return label;
    }
}
