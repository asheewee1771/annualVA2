package sg.annualva.dashboard;

import sg.annualva.submission.SubmissionState;

/**
 * How a System in a Quarter is shown on a dashboard: Not Started while it has no VA Submission, otherwise its VA
 * Submission's state. The order is the spec's state table, which is the order of the Quarter board's columns.
 */
public enum SystemState {
    NOT_STARTED,
    DRAFT,
    IN_REVIEW,
    RETURNED_FOR_REWORK,
    AWAITING_ENDORSEMENT,
    ENDORSED,
    EXEMPTION_REQUESTED,
    EXEMPTED,
    WITHDRAWN,
    NOT_ENDORSED;

    /** Each VA Submission state by name, so a state added to one enum and not the other fails to compile. */
    static SystemState of(SubmissionState state) {
        return switch (state) {
            case DRAFT -> DRAFT;
            case IN_REVIEW -> IN_REVIEW;
            case RETURNED_FOR_REWORK -> RETURNED_FOR_REWORK;
            case AWAITING_ENDORSEMENT -> AWAITING_ENDORSEMENT;
            case ENDORSED -> ENDORSED;
            case EXEMPTION_REQUESTED -> EXEMPTION_REQUESTED;
            case EXEMPTED -> EXEMPTED;
            case WITHDRAWN -> WITHDRAWN;
            case NOT_ENDORSED -> NOT_ENDORSED;
        };
    }

    /** Endorsed, Exempted, Withdrawn and Not Endorsed are final: nothing more is done (spec state table). */
    boolean isFinal() {
        return switch (this) {
            case ENDORSED, EXEMPTED, WITHDRAWN, NOT_ENDORSED -> true;
            case NOT_STARTED, DRAFT, IN_REVIEW, RETURNED_FOR_REWORK, AWAITING_ENDORSEMENT, EXEMPTION_REQUESTED -> false;
        };
    }
}
