package sg.annualva.submission;

import java.util.Arrays;
import java.util.Optional;
import sg.annualva.access.Need;

/**
 * The steps of a VA Submission at which a person acts; segregation of duties allows one person only one of them. The
 * person assigned to a step of a System in the Register (its SMO/Tech Lead submits, its Cyber Manager reviews) counts as
 * acting at that step even before they do. Nobody is assigned to the endorsing step: a VA POC counts there only once
 * they act (ADR 0039 item 5).
 */
public enum SubmissionStep {
    /** Starting and submitting: the SMO/Tech Lead. */
    SUBMIT(Need.SUBMIT, true),
    /** Confirming or Disputing Findings, returning and passing to endorsement: the Cyber Manager (ticket 28). */
    REVIEW(Need.REVIEW, true),
    /** Endorsing (ticket 34), and later a VA POC's return and Exemption decisions: a VA POC, by acting only. */
    ENDORSE(Need.ENDORSE, false);

    private final Need need;
    private final boolean assignedInRegister;

    SubmissionStep(Need need, boolean assignedInRegister) {
        this.need = need;
        this.assignedInRegister = assignedInRegister;
    }

    /**
     * Who is assigned to the step of a System before they act: those the access guard gives this need over it; none for
     * the endorsing step.
     */
    Optional<Need> assignedBy() {
        return assignedInRegister ? Optional.of(need) : Optional.empty();
    }

    /**
     * The step a person with this need acts at, for a read model that marks segregation-of-duties conflicts; none for a
     * need that acts at no step (viewing, reading the audit history).
     */
    public static Optional<SubmissionStep> actingFor(Need need) {
        return Arrays.stream(values()).filter(step -> step.need == need).findFirst();
    }
}
