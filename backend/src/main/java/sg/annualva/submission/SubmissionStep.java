package sg.annualva.submission;

import java.util.Arrays;
import java.util.Optional;
import sg.annualva.access.Need;

/**
 * The steps of a VA Submission at which a person acts; segregation of duties allows one person only one of them. The
 * person assigned to a step of a System in the Register (its SMO/Tech Lead submits, its Cyber Manager reviews) counts as
 * acting at that step even before they do.
 */
public enum SubmissionStep {
    /** Starting and submitting: the SMO/Tech Lead. */
    SUBMIT(Need.SUBMIT),
    /** Confirming or Disputing Findings, returning and passing to endorsement: the Cyber Manager (ticket 28). */
    REVIEW(Need.REVIEW);

    private final Need need;

    SubmissionStep(Need need) {
        this.need = need;
    }

    /** Who is assigned to the step of a System: those the access guard gives this need over it. */
    Need need() {
        return need;
    }

    /**
     * The step a person with this need acts at, for a read model that marks segregation-of-duties conflicts; none for a
     * need no step records yet (endorsing, until ticket 34 adds its step) or that acts at no step (viewing).
     */
    public static Optional<SubmissionStep> actingFor(Need need) {
        return Arrays.stream(values()).filter(step -> step.need == need).findFirst();
    }
}
