package sg.annualva.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AppUserStore;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.Role;
import sg.annualva.access.RoleHolders;
import sg.annualva.access.VaPocProperties;
import sg.annualva.register.RegisterSystems;
import sg.annualva.submission.SubmissionStep;
import sg.annualva.web.Page;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The notifications (stories 92 and 69, ADR 0043): the one port through which anything tells a person something, and
 * each person's in-app list. A sender hands over a {@link Notice} and says who it is for: the System's SMO/Tech Lead or
 * Cyber Manager as the Register names them now, a named person, or both VA POCs as a pair that clears for both once
 * either makes the decision it {@link Awaits}. Senders call it after the move they report has committed
 * ({@code WorkflowNotices}), so a refused or rolled-back command tells nobody. A person the Register names who has not
 * signed in yet is pre-registered by email and finds the notification at the first sign-in. A required message is also
 * queued for email in the same transaction ({@link MailOutbox}, ADR 0046), so the mail and the in-app item are one
 * notification.
 */
@Service
public class Notifications {

    private final NotificationStore store;
    private final RegisterSystems register;
    private final AppUserStore users;
    private final VaPocProperties vaPocs;
    private final CurrentUser user;
    private final Clock clock;
    private final MailOutbox mail;
    private final RoleHolders roleHolders;

    Notifications(NotificationStore store, RegisterSystems register, AppUserStore users, VaPocProperties vaPocs,
            CurrentUser user, Clock clock, MailOutbox mail, RoleHolders roleHolders) {
        this.roleHolders = roleHolders;
        this.mail = mail;
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

    /**
     * Tells whoever acts at this step of the System's VA Submission (ADR 0043 item 3, ADR 0052), linked where they take
     * it up: the one rule of who is told at a step and where their link points. The SMO/Tech Lead at the submitting step,
     * at their place for it in its state ({@link Links#forSmo}: the inbox while it is Not Started, the rework queue once
     * it is Returned for Rework, otherwise the VA Submission); the Cyber Manager at the reviewing step, at the review;
     * both VA POCs, as an endorsement pair, at the endorsing step, at the endorsement.
     *
     * <p>When the notice's state waits on the step and the person the Register names for it cannot act, because their
     * grant for the System is suspended or lapsed (decided 2026-10-26), nobody can: both VA POCs are told instead
     * ({@link #nobodyAt}). A notice about a state that waits on no step (an approval, a close, a confirmation) is theirs
     * to read whatever their grant says, as a copy ({@link #copyUp}) is.
     */
    @Transactional
    public void toStep(SubmissionStep step, Notice.About about, NoticeAction action) {
        switch (step) {
            case SUBMIT -> toHolder(step, Role.SMO, RegisterSystems.Named::smoEmail, about,
                    about.tell(action, Links.forSmo(about.state(), about.submissionId())));
            case REVIEW -> toHolder(step, Role.CYBER_MANAGER, RegisterSystems.Named::cyberManagerEmail, about,
                    about.tell(action, Links.review(started(about))));
            case ENDORSE -> toVaPocs(about.tell(action, Links.endorse(started(about))), Awaits.ENDORSEMENT);
        }
    }

    /** To the person the Register names for the step's role, or, when nobody can act at the step, the VA POCs. */
    private void toHolder(SubmissionStep step, Role role, Function<RegisterSystems.Named, String> named,
            Notice.About about, Notice notice) {
        register.named(about.systemId()).ifPresent(system -> {
            String email = named.apply(system);
            boolean waitsOnStep = about.state().nextStep().filter(step::equals).isPresent();
            if (waitsOnStep && roleHolders.cannotAct(role, email, about.systemId())) {
                nobodyAt(step, about);
            } else {
                to(email, notice);
            }
        });
    }

    /**
     * Nobody can act at the step: both VA POCs are told, as a pair awaiting a role holder ({@link Awaits#ROLE_HOLDER});
     * it names the System, the state and the step, links the role-holder review, and carries no address and no free
     * text. The notice it replaces keeps its own rhythm: a reminder is written once per day (ADR 0052 item 7), the
     * Overdue message once. Every open pair clears once a VA POC restores the role or applies a Register naming someone
     * else ({@link #roleHolderChanged}).
     */
    private void nobodyAt(SubmissionStep step, Notice.About about) {
        toVaPocs(about.tell(nobodyAt(step), Links.ROLE_HOLDERS), Awaits.ROLE_HOLDER);
    }

    /** What the VA POCs are told when nobody can act at a step; the endorsing step is theirs, so never. */
    private static NoticeAction nobodyAt(SubmissionStep step) {
        return switch (step) {
            case SUBMIT -> NoticeAction.NOBODY_TO_SUBMIT;
            case REVIEW -> NoticeAction.NOBODY_TO_REVIEW;
            case ENDORSE -> throw new IllegalArgumentException("the VA POCs act at the endorsing step themselves");
        };
    }

    /**
     * A VA POC gave the System someone who can act at the role's step again (restored the role, or applied a Register
     * naming someone else): every open pair that told both VA POCs nobody could act there is cleared for both.
     */
    @Transactional
    public void roleHolderChanged(UUID systemId, Role role, UUID handler, Instant at) {
        store.handleOnSystem(systemId, nobodyAt(role == Role.SMO ? SubmissionStep.SUBMIT : SubmissionStep.REVIEW),
                handler, at);
    }

    /**
     * A weekly Overdue reminder's copy for the next person up from this step (ADR 0052 item 3), a message of its own since
     * the relay has no cc: the Cyber Manager for the submitting step, linked to their inbox (a Draft is not theirs to
     * open); each VA POC for the reviewing step, linked to the VA Submission. The endorsing step has no one above it.
     */
    @Transactional
    public void copyUp(SubmissionStep step, Notice.About about, NoticeAction action) {
        switch (step) {
            case SUBMIT -> toCyberManager(about.tell(action, Links.CYBER_MANAGER_INBOX));
            case REVIEW -> toEachVaPoc(about.tell(action, Links.endorse(started(about))));
            case ENDORSE -> throw new IllegalArgumentException("the endorsing step has no one above it to copy");
        }
    }

    private static UUID started(Notice.About about) {
        return Objects.requireNonNull(about.submissionId(), "a step after submitting has a VA Submission");
    }

    /** To one person, known by email (a transfer's former SMO/Tech Lead, whom the Register no longer names). */
    @Transactional
    public void to(String email, Notice notice) {
        write(email, notice, null, null);
    }

    /**
     * To both VA POCs (story 69): one row each, sharing a group key, both cleared with the handler's name once either VA
     * POC makes the decision the item awaits ({@link #handled}).
     */
    @Transactional
    public void toVaPocs(Notice notice, Awaits awaits) {
        UUID groupKey = UUID.randomUUID();
        vaPocEmails().forEach(email -> write(email, notice, groupKey, awaits));
    }

    /**
     * To each VA POC as an item of their own, which nothing clears: a copy for their information (ADR 0052), not a
     * decision they await.
     */
    @Transactional
    public void toEachVaPoc(Notice notice) {
        vaPocEmails().forEach(email -> write(email, notice, null, null));
    }

    /** The configured VA POCs, each once, by lower-case email. */
    private Set<String> vaPocEmails() {
        Set<String> people = new LinkedHashSet<>();
        vaPocs.vaPocs().forEach(email -> people.add(email.toLowerCase(Locale.ROOT)));
        return people;
    }

    /**
     * One person's notification, and its mail when the notice is a required message, in the caller's transaction; a
     * reminder they already have for the same day is neither written nor mailed again.
     */
    private void write(String email, Notice notice, @Nullable UUID groupKey, @Nullable Awaits awaits) {
        store.insert(users.known(email, notice.at()), notice, groupKey, awaits)
                .ifPresent(id -> mail.queue(id, notice.action(), notice.at()));
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
        return new Page<>(views(store.page(caller, page, size)), page, size, store.count(caller));
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
        return views(List.of(store.find(id, caller).orElseThrow(ResourceNotFoundException::new))).getFirst();
    }

    /**
     * The notifications as their reader lists them: each with its System's ID and name as the Register holds them now,
     * read through the Register's module, retired Systems included.
     */
    private List<NotificationView> views(List<NotificationStore.Row> rows) {
        Map<UUID, RegisterSystems.Named> systems = register.named(rows.stream().map(NotificationStore.Row::systemId)
                .toList());
        return rows.stream().map(row -> {
            RegisterSystems.Named system = Objects.requireNonNull(systems.get(row.systemId()),
                    "a notification's System is in the Register (V27's foreign key)");
            return new NotificationView(row.id(), row.systemId(), system.systemId(), system.name(),
                    row.submissionId(), row.state(), row.action().name(), row.action().message(), row.dueOn(),
                    row.link(), row.createdAt(), row.readAt(), row.handledBy(), row.handledAt());
        }).toList();
    }
}
