package sg.annualva.harness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.event.TransactionalEventListener;
import sg.annualva.submission.CycleClosed;

/**
 * Listens to the VA Cycle closes the way the notification tickets will: after the close's transaction commits. A test reads
 * what was published; a close that was refused or rolled back published nothing.
 */
public class RecordedCycleEvents {

    private final List<CycleClosed> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void on(CycleClosed event) {
        events.add(event);
    }

    /** Every close published after a commit since the last {@link #reset}, in order. */
    public List<CycleClosed> published() {
        return List.copyOf(events);
    }

    public void reset() {
        events.clear();
    }
}
