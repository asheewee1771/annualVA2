package sg.annualva.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AppUserStore;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.VaPocProperties;
import sg.annualva.register.RegisterSystems;
import sg.annualva.web.Page;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The notifications (stories 92 and 69, ADR 0043): the one port through which anything tells a person something, and
 * each person's in-app list. A sender hands over a {@link Notice} and says who it is for: the System's SMO/Tech Lead or
 * Cyber Manager as the Register names them now, a named person, or both VA POCs as a pair that clears for both once
 * either makes the decision it {@link Awaits}. Senders call it after the move they report has committed
 * ({@code WorkflowNotices}), so a refused or rolled-back command tells nobody. A person the Register names who has not
 * signed in yet is pre-registered by email and finds the notification at the first sign-in. The mail of ticket 37 goes
 * out from here too.
 */
@Service
public class Notifications {

    private final NotificationStore store;
    private final RegisterSystems register;
    private final AppUserStore users;
    private final VaPocProperties vaPocs;
    private final CurrentUser user;
    private final Clock clock;

    Notifications(NotificationStore store, RegisterSystems register, AppUserStore users, VaPocProperties vaPocs,
            CurrentUser user, Clock clock) {
        this.store = store;
        this.register = register;
        this.users = users;
        this.vaPocs = vaPocs;
        this.user = user;
        this.clock = clock;
    }

    /** To the System's SMO/Tech Lead, as the Register names them now. */
    @Transactional
    public void toSmo(Notice notice) {
        register.named(notice.systemId()).ifPresent(system -> to(system.smoEmail(), notice));
    }

    /** To the System's Cyber Manager, as the Register names them now. */
    @Transactional
    public void toCyberManager(Notice notice) {
        register.named(notice.systemId()).ifPresent(system -> to(system.cyberManagerEmail(), notice));
    }

    /** To one person, known by email (a transfer's former SMO/Tech Lead, whom the Register no longer names). */
    @Transactional
    public void to(String email, Notice notice) {
        store.insert(users.known(email, notice.at()), notice, null, null);
    }

    /**
     * To both VA POCs (story 69): one row each, sharing a group key, both cleared with the handler's name once either VA
     * POC makes the decision the item awaits ({@link #handled}).
     */
    @Transactional
    public void toVaPocs(Notice notice, Awaits awaits) {
        UUID groupKey = UUID.randomUUID();
        Set<String> people = new LinkedHashSet<>();
        vaPocs.vaPocs().forEach(email -> people.add(email.toLowerCase(Locale.ROOT)));
        people.forEach(email -> store.insert(users.known(email, notice.at()), notice, groupKey, awaits));
    }

    /**
     * A VA POC made the decision these items await on the VA Submission: every open pair of it is cleared for both VA
     * POCs, marked with who handled it and when.
     */
    @Transactional
    public void handled(UUID submissionId, Awaits awaits, UUID handler, Instant at) {
        store.handle(submissionId, awaits, handler, at);
    }

    /** The caller's notifications, newest first; a read changes nothing and is not audited (ADR 0043). */
    @Transactional(readOnly = true)
    public Page<NotificationView> mine(int page, int size) {
        UUID caller = user.id();
        return new Page<>(store.page(caller, page, size), page, size, store.count(caller));
    }

    /**
     * Marks one of the caller's notifications read, the first time only. Another person's, and one that does not exist,
     * are "not found" alike (ADR 0013).
     */
    @Transactional
    public NotificationView read(UUID id) {
        UUID caller = user.id();
        if (!store.markRead(id, caller, clock.instant())) {
            throw new ResourceNotFoundException();
        }
        return store.find(id, caller).orElseThrow(ResourceNotFoundException::new);
    }
}
