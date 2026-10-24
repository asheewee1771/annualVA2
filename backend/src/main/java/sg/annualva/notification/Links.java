package sg.annualva.notification;

import java.util.UUID;
import sg.annualva.submission.SubmissionState;

/**
 * The app paths a notification links to, the same paths the React app routes (its {@code paths.ts} per role): fixed
 * segments and ids only, so a link carries nothing a person typed. A mail (ticket 37) puts the app's address in front.
 */
public final class Links {

    /** The SMO/Tech Lead's inbox: where a System with no VA Submission is started. */
    public static final String SMO_INBOX = "/smo";

    /** The VA POCs' queue of pending Exemption requests. */
    public static final String EXEMPTION_QUEUE = "/va-poc/exemptions";

    private Links() {}

    /**
     * Where the SMO/Tech Lead takes up a VA Submission in this state: their inbox while it is Not Started (to start it),
     * the rework queue once it is Returned for Rework, and otherwise the VA Submission itself.
     */
    public static String forSmo(SubmissionState state, UUID submissionId) {
        return switch (state) {
            case NOT_STARTED -> SMO_INBOX;
            case RETURNED_FOR_REWORK -> rework(submissionId);
            default -> prepare(submissionId);
        };
    }

    /** Where the SMO/Tech Lead prepares a VA Submission. */
    public static String prepare(UUID submissionId) {
        return SMO_INBOX + "/submissions/" + submissionId;
    }

    /** Where the SMO/Tech Lead works through a VA Submission Returned for Rework. */
    public static String rework(UUID submissionId) {
        return prepare(submissionId) + "/rework";
    }

    /** Where the Cyber Manager reviews a VA Submission. */
    public static String review(UUID submissionId) {
        return "/cyber-manager/submissions/" + submissionId;
    }

    /** Where a VA POC reads a VA Submission and endorses or returns it. */
    public static String endorse(UUID submissionId) {
        return "/va-poc/submissions/" + submissionId;
    }
}
