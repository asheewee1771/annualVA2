package sg.annualva.harness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.event.TransactionalEventListener;
import sg.annualva.submission.SubmissionEvent;

/**
 * Listens to the VA Submission events the way the notification tickets will: after the command's transaction commits. A
 * test reads what was published; a command that was refused or rolled back published nothing.
 */
public class RecordedSubmissionEvents {

    private final List<SubmissionEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void on(SubmissionEvent event) {
        events.add(event);
    }

    /** Every event published after a commit since the last {@link #reset}, in order. */
    public List<SubmissionEvent> published() {
        return List.copyOf(events);
    }

    public void reset() {
        events.clear();
    }
}
