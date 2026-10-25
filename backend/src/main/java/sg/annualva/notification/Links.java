package sg.annualva.notification;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import sg.annualva.submission.SubmissionState;
import sg.annualva.submission.SubmissionStep;

/**
 * The app paths a notification links to, the same paths the React app routes (its {@code paths.ts} per role): fixed
 * segments and ids only, so a link carries nothing a person typed. A mail (ticket 37) puts the app's address in front.
 */
public final class Links {

    /** The SMO/Tech Lead's inbox: where a System with no VA Submission is started. */
    public static final String SMO_INBOX = "/smo";

    /** The Cyber Manager's inbox: where a copy of an SMO/Tech Lead's reminder points, whatever the state (ticket 38). */
    public static final String CYBER_MANAGER_INBOX = "/cyber-manager";

    /** The VA POCs' queue of pending Exemption requests. */
    public static final String EXEMPTION_QUEUE = "/va-poc/exemptions";

    private Links() {}

    /**
     * Where the SMO/Tech Lead takes up a VA Submission in this state: their inbox while it is Not Started (to start it),
     * the rework queue once it is Returned for Rework, and otherwise the VA Submission itself.
     *
     * @param submissionId null for a System with no VA Submission, which is Not Started
     */
    public static String forSmo(SubmissionState state, @Nullable UUID submissionId) {
        if (state == SubmissionState.NOT_STARTED) {
            return SMO_INBOX;
        }
        UUID id = Objects.requireNonNull(submissionId, "a state after Not Started has a VA Submission");
        return state == SubmissionState.RETURNED_FOR_REWORK ? rework(id) : prepare(id);
    }

    /**
     * Where the person who acts at this step takes up a VA Submission in this state (ADR 0052): the SMO/Tech Lead's place
     * for it ({@link #forSmo}) at the submitting step, the review at the reviewing step and the endorsement at the
     * endorsing step. The one link rule of every notice to whoever acts next: a move, an Overdue flag, a lapse and a
     * deadline reminder; {@link Notifications#toStep} is the matching recipient rule.
     *
     * @param submissionId null for a System with no VA Submission, which is Not Started
     */
    public static String forStep(SubmissionStep step, SubmissionState state, @Nullable UUID submissionId) {
        return switch (step) {
            case SUBMIT -> forSmo(state, submissionId);
            case REVIEW -> review(Objects.requireNonNull(submissionId, "a VA Submission in review has an id"));
            case ENDORSE -> endorse(Objects.requireNonNull(submissionId, "a VA Submission at endorsement has an id"));
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
