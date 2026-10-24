package sg.annualva.harness;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import sg.annualva.boundary.BoundaryFailureException;
import sg.annualva.boundary.MailRelay;

/**
 * The mail-relay fake (spec, Testing Decisions): it captures every message it accepts, with the recipient, the template
 * id and the values, and gives each a message id. A test can make it refuse an address (the reject-address knob, as the
 * notification service refuses an address it cannot deliver to) or fail the next calls (a relay that cannot be reached).
 */
public final class CapturingMailRelay implements MailRelay {

    /** A message the relay accepted. */
    public record Sent(String recipient, String templateId, Map<String, String> values, String messageId) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Set<String> rejected = ConcurrentHashMap.newKeySet();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * Thrown in place of an answer to stand for a dispatcher that stopped mid-call (the instance died): it is an
     * {@link Error}, so nothing in the application catches it and the claim is left as it was.
     */
    public static final class DispatcherStopped extends Error {
        private static final long serialVersionUID = 1L;

        DispatcherStopped() {
            super("The dispatcher stopped while the relay had the mail", null, false, false);
        }
    }

    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicReference<Runnable> beforeAnswer = new AtomicReference<>();

    @Override
    public Receipt send(String recipient, String templateId, Map<String, String> values) {
        calls.incrementAndGet();
        Runnable hook = beforeAnswer.getAndSet(null);
        if (hook != null) {
            hook.run();
        }
        if (stops.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new DispatcherStopped();
        }
        if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new BoundaryFailureException("The fake mail relay cannot be reached");
        }
        if (rejected.contains(recipient.toLowerCase(Locale.ROOT))) {
            return new AddressRejected();
        }
        String messageId = "relay-message-" + counter.incrementAndGet();
        sent.add(new Sent(recipient, templateId, Map.copyOf(values), messageId));
        return new Accepted(messageId);
    }

    /** The relay refuses this address from now on. */
    public void rejectAddress(String email) {
        rejected.add(email.toLowerCase(Locale.ROOT));
    }

    /** The next {@code count} calls fail as if the relay could not be reached. */
    public void failNext(int count) {
        failures.set(count);
    }

    /**
     * Runs this once, inside the next call and before the relay answers it: a test uses it to let a call outlast its lease
     * while another pass claims the same mail.
     */
    public void beforeNextAnswer(Runnable hook) {
        beforeAnswer.set(hook);
    }

    /** The next call stops the dispatcher mid-call, before the relay answers ({@link DispatcherStopped}). */
    public void stopNext() {
        stops.set(1);
    }

    /** Every message the relay accepted, in order. */
    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** How many times the relay was called, whatever it answered. */
    public int calls() {
        return calls.get();
    }

    /** Back to a relay that accepts every address and has seen nothing. */
    public void reset() {
        sent.clear();
        rejected.clear();
        failures.set(0);
        stops.set(0);
        beforeAnswer.set(null);
        calls.set(0);
        counter.set(0);
    }
}
