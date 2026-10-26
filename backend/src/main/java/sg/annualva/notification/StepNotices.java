package sg.annualva.notification;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import sg.annualva.submission.SubmissionStep;

/**
 * What a person is told at each step of a VA Submission (ADR 0043 item 3, ADR 0052): the one per-step table that the
 * move, Overdue and lapse notices and the deadline reminders read. Who is told at a step, and where their link points,
 * are the port's ({@link Notifications#toStep}, and {@link Notifications#copyUp} for a weekly reminder's copy).
 *
 * @param arrived what the person is told when work reaches their step after someone else's (the Cyber Manager to review,
 *     both VA POCs to endorse); none at the submitting step, where a return or a lapse says it in words of its own
 * @param overdue what they are told once the Overdue sweep flags the step (ADR 0033)
 * @param daysBefore the reminder for each number of days left before the step's window ends
 * @param weekly the reminder every week while the step stays Overdue, and its copy to the next person up; null for
 *     endorsing (story 90 asks for none, and the sweep's Overdue message tells both VA POCs)
 */
record StepNotices(@Nullable NoticeAction arrived, NoticeAction overdue, Map<Long, NoticeAction> daysBefore,
        @Nullable Weekly weekly) {

    /** The weekly reminder while a step is Overdue, and its copy for the next person up. */
    record Weekly(NoticeAction action, NoticeAction copy) {}

    /** The table, written once. */
    static StepNotices of(SubmissionStep step) {
        return switch (step) {
            case SUBMIT -> new StepNotices(null, NoticeAction.SUBMIT_OVERDUE,
                    Map.of(5L, NoticeAction.SUBMIT_DUE_SOON, 0L, NoticeAction.SUBMIT_DUE_TODAY),
                    new Weekly(NoticeAction.SUBMIT_STILL_OVERDUE, NoticeAction.SUBMIT_OVERDUE_COPY));
            case REVIEW -> new StepNotices(NoticeAction.REVIEW, NoticeAction.REVIEW_OVERDUE,
                    Map.of(5L, NoticeAction.REVIEW_DUE_SOON, 0L, NoticeAction.REVIEW_DUE_TODAY),
                    new Weekly(NoticeAction.REVIEW_STILL_OVERDUE, NoticeAction.REVIEW_OVERDUE_COPY));
            case ENDORSE -> new StepNotices(NoticeAction.ENDORSE, NoticeAction.ENDORSE_OVERDUE,
                    Map.of(14L, NoticeAction.ENDORSE_DUE_IN_TWO_WEEKS, 7L, NoticeAction.ENDORSE_DUE_IN_ONE_WEEK), null);
        };
    }

    /** What the person is told when work reaches this step after someone else's; none at the submitting step. */
    Optional<NoticeAction> onArrival() {
        return Optional.ofNullable(arrived);
    }
}
