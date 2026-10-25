package sg.annualva.notification;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import sg.annualva.submission.SubmissionStep;

/**
 * What a person is told at each step of a VA Submission (ADR 0043 item 3, ADR 0052): the one per-step table that the
 * move, Overdue and lapse notices and the deadline reminders read. Who is told at a step, and where their link points,
 * are the port's ({@link Notifications#toStep}).
 *
 * @param arrived what the person is told when work reaches their step after someone else's (the Cyber Manager to review,
 *     both VA POCs to endorse); null at the submitting step, where a return or a lapse says it in words of its own
 * @param overdue what they are told once the Overdue sweep flags the step (ADR 0033)
 * @param daysBefore the reminder for each number of days left before the step's window ends
 * @param weekly the reminder every week while the step stays Overdue, and its copy to the next person up; null for
 *     endorsing (story 90 asks for none, and the sweep's Overdue message tells both VA POCs)
 */
record StepNotices(@Nullable NoticeAction arrived, NoticeAction overdue, Map<Long, NoticeAction> daysBefore,
        @Nullable Weekly weekly) {

    /**
     * The weekly reminder while a step is Overdue, and its copy: a message of its own, since the relay has no cc, to
     * whom {@code copyTo} names, linked by {@code copyLink}.
     */
    record Weekly(NoticeAction action, NoticeAction copy, BiConsumer<Notifications, Notice> copyTo,
            Function<Notice.About, String> copyLink) {}

    /** The table, written once. */
    static StepNotices of(SubmissionStep step) {
        return switch (step) {
            case SUBMIT -> new StepNotices(null, NoticeAction.SUBMIT_OVERDUE,
                    Map.of(5L, NoticeAction.SUBMIT_DUE_SOON, 0L, NoticeAction.SUBMIT_DUE_TODAY),
                    new Weekly(NoticeAction.SUBMIT_STILL_OVERDUE, NoticeAction.SUBMIT_OVERDUE_COPY,
                            Notifications::toCyberManager, about -> Links.CYBER_MANAGER_INBOX));
            case REVIEW -> new StepNotices(NoticeAction.REVIEW, NoticeAction.REVIEW_OVERDUE,
                    Map.of(5L, NoticeAction.REVIEW_DUE_SOON, 0L, NoticeAction.REVIEW_DUE_TODAY),
                    new Weekly(NoticeAction.REVIEW_STILL_OVERDUE, NoticeAction.REVIEW_OVERDUE_COPY,
                            Notifications::toEachVaPoc, about -> Links.endorse(Objects.requireNonNull(
                                    about.submissionId(), "a VA Submission in review has an id"))));
            case ENDORSE -> new StepNotices(NoticeAction.ENDORSE, NoticeAction.ENDORSE_OVERDUE,
                    Map.of(14L, NoticeAction.ENDORSE_DUE_IN_TWO_WEEKS, 7L, NoticeAction.ENDORSE_DUE_IN_ONE_WEEK), null);
        };
    }
}
