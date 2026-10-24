package sg.annualva.notification;

/**
 * What a notification asks its reader to do, or tells them, as a fixed code with the app's own sentence (story 91: only
 * the System, the state, the action, the due date and a link; never a Finding). A ticket that sends a new kind adds it
 * here and to the {@code notification_action_check} of its migration.
 */
public enum NoticeAction {
    /** To the Cyber Manager, once a VA Submission is submitted. */
    REVIEW("Review the VA Submission."),
    /** To the SMO/Tech Lead, once a VA Submission is Returned for Rework with what is Disputed. */
    REWORK("Rework what is Disputed and submit the VA Submission again."),
    /** To both VA POCs, once a VA Submission is passed to endorsement. */
    ENDORSE("Endorse the VA Submission, or return it for rework."),
    /** To the SMO/Tech Lead, once the Submission Window, or the window of a return, has passed. */
    SUBMIT_OVERDUE("Overdue: prepare and submit the VA Submission."),
    /** To the Cyber Manager, once the Review Window has passed. */
    REVIEW_OVERDUE("Overdue: review the VA Submission."),
    /** To both VA POCs, once the Quarter's last day has passed. */
    ENDORSE_OVERDUE("Overdue: endorse the VA Submission, or return it for rework."),
    /** To the SMO/Tech Lead, once the Register moves the System to another Quarter. */
    SYSTEM_MOVED("The System Register moved this System to another Quarter."),
    /** To the new SMO/Tech Lead of a System. */
    SYSTEM_TRANSFERRED_TO_YOU("The System Register made you this System's SMO/Tech Lead."),
    /** To the SMO/Tech Lead a System was transferred from. */
    SYSTEM_TRANSFERRED_FROM_YOU("The System Register gave this System another SMO/Tech Lead."),
    /**
     * To the SMO/Tech Lead, once the VA Cycle closed with the System neither Endorsed nor Exempted (ticket 41): its VA
     * Submission, or the one the close made for a System that had none, is Not Endorsed.
     */
    NOT_ENDORSED("The VA Cycle closed before this System's VA Submission was endorsed: it is Not Endorsed."),
    /**
     * To the SMO/Tech Lead, once a VA POC returned the VA Submission (ticket 35): nothing is Disputed, so the sentence
     * points at the VA POC's comment, which the notification never carries.
     */
    VA_POC_REWORK("A VA POC returned the VA Submission: read their comment, rework it and submit it again."),
    /** To the Cyber Manager, once a VA POC returned a VA Submission they passed to endorsement (ticket 35). */
    VA_POC_RETURNED("A VA POC returned the VA Submission you passed: re-review what changes once it is submitted again.");

    private final String message;

    NoticeAction(String message) {
        this.message = message;
    }

    /** The sentence the screen shows: fixed text, never anything a person typed. */
    public String message() {
        return message;
    }
}
