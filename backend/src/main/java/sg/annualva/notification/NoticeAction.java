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
    /**
     * To the System's Cyber Manager, once a VA POC returned its VA Submission from Awaiting Endorsement (ticket 35): the
     * resubmission comes back to them, whoever passed it.
     */
    VA_POC_RETURNED("A VA POC returned the VA Submission from endorsement: re-review what changes once it is submitted "
            + "again."),
    /** To both VA POCs, once an SMO/Tech Lead requests an Exemption (ticket 40); either one's decision clears it. */
    DECIDE_EXEMPTION("Approve or reject the Exemption request."),
    /** To the SMO/Tech Lead, once a VA POC approves their Exemption. */
    EXEMPTION_APPROVED("A VA POC approved the Exemption: this System is Exempted."),
    /** To the SMO/Tech Lead, once a VA POC rejects their Exemption and the VA Submission is back where it was. */
    EXEMPTION_REJECTED("A VA POC rejected the Exemption: the VA Submission is back where it was before the request."),
    /**
     * To the SMO/Tech Lead the Register last named, once a Register apply retired the System and its in-flight VA
     * Submission became Withdrawn (ticket 43, story 94): nothing more is due from them.
     */
    WITHDRAWN("The System Register retired this System, so its VA Submission is Withdrawn: nothing more is due."),
    /**
     * To both VA POCs, once the SMO/Tech Lead records that a conditional Exemption's scheduled VA happened (ticket 42);
     * either one's confirmation clears it.
     */
    CONFIRM_VA("Confirm the VA the SMO/Tech Lead recorded for this conditional Exemption."),
    /** To the SMO/Tech Lead, once a VA POC confirms the recorded VA. */
    VA_CONFIRMED("A VA POC confirmed the VA: the Exemption is no longer conditional."),
    /**
     * To the SMO/Tech Lead, once a conditional Exemption lapsed while its VA Cycle is open: the VA Submission is back where
     * it was before the request, with new windows.
     */
    EXEMPTION_LAPSED("The conditional Exemption lapsed because its VA was not recorded in time: the VA Submission is "
            + "back where it was before the request, with new windows."),
    /** To the SMO/Tech Lead, once a conditional Exemption lapsed after its VA Cycle closed. */
    EXEMPTION_LAPSED_NOT_ENDORSED("The conditional Exemption lapsed because its VA was not recorded in time, after the "
            + "VA Cycle closed: it is Not Endorsed.");

    private final String message;

    NoticeAction(String message) {
        this.message = message;
    }

    /** The sentence the screen shows: fixed text, never anything a person typed. */
    public String message() {
        return message;
    }
}
