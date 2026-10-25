package sg.annualva.submission;

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import sg.annualva.access.Need;
import sg.annualva.access.Role;

/**
 * The steps of a VA Submission at which a person acts; segregation of duties allows one person only one of them. The
 * person assigned to a step of a System in the Register (its SMO/Tech Lead submits, its Cyber Manager reviews) counts as
 * acting at that step even before they do. Nobody is assigned to the endorsing step: a VA POC counts there only once
 * they act (ADR 0039 item 5).
 */
public enum SubmissionStep {
    /** Starting and submitting: the SMO/Tech Lead. */
    SUBMIT(Need.SUBMIT, Role.SMO),
    /** Confirming or Disputing Findings, returning and passing to endorsement: the Cyber Manager (ticket 28). */
    REVIEW(Need.REVIEW, Role.CYBER_MANAGER),
    /** Endorsing and returning (tickets 34 and 35), and later Exemption decisions: a VA POC, by acting only. */
    ENDORSE(Need.ENDORSE, null);

    private final Need need;
    private final @Nullable Role assignedInRegister;

    SubmissionStep(Need need, @Nullable Role assignedInRegister) {
        this.need = need;
        this.assignedInRegister = assignedInRegister;
    }

    /** What a person needs over a System to act at the step (the access guard's ownership check). */
    Need need() {
        return need;
    }

    /**
     * Who is assigned to the step of a System before they act: those the access guard gives this need over it; none for
     * the endorsing step.
     */
    Optional<Need> assignedBy() {
        return assignedInRegister == null ? Optional.empty() : Optional.of(need);
    }

    /** The Register role assigned to the step, whose holder work at the step is handed to; none for the endorsing step. */
    Optional<Role> assignedRole() {
        return Optional.ofNullable(assignedInRegister);
    }

    /**
     * The step a person with this need acts at, for a read model that marks segregation-of-duties conflicts; none for a
     * need that acts at no step (viewing, reading the audit history).
     */
    public static Optional<SubmissionStep> actingFor(Need need) {
        return Arrays.stream(values()).filter(step -> step.need == need).findFirst();
    }
}
