package sg.annualva.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.StepUp;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.register.RegisterSystems;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The disposal lists as a VA POC reads and approves them (story 87). Approving is a step-up action (IM8 ac-2): without a
 * sign-in from the last five minutes it is refused before anything is read. Only the list waiting for approval can be
 * approved: one the job has since replaced, or one already approved or executed, is refused, so a VA POC approves
 * exactly the list they read. The executor ({@link DisposalExecutor}) then disposes of it.
 */
@Service
public class DisposalLists {

    /** The step-up intent of approving a disposal list. */
    static final String APPROVE_INTENT = "disposal-approve";

    /** How many lists the screen shows: the waiting one and the latest executed ones. */
    private static final int SHOWN = 20;

    /**
     * A disposal list as the screen shows it, its items in the order they were listed.
     *
     * @param cutoff the instant before which the records of the Cycles it disposes of were written; null when it disposes
     *     of no Cycle
     */
    public record ListView(
            UUID id, ListStatus status, LocalDate computedOn, Instant createdAt, @Nullable Instant cutoff,
            @Nullable UUID approvedBy, @Nullable Instant approvedAt, @Nullable Instant executedAt, List<ItemView> items) {}

    /**
     * One item, with the System it is about named as the Register holds it now (null once the System is gone).
     *
     * @param entries for the audit range, how many entries were before the cutoff when it was listed
     */
    public record ItemView(
            ItemKind kind, @Nullable UUID targetId, @Nullable UUID systemId, @Nullable String systemCode,
            @Nullable String systemName, @Nullable Integer cycle, DisposalReason reason, @Nullable Long entries) {}

    private final DisposalStore store;
    private final RegisterSystems systems;
    private final StepUp stepUp;
    private final CurrentUser user;
    private final AuditLog audit;
    private final AuditActor actor;
    private final Clock clock;

    DisposalLists(DisposalStore store, RegisterSystems systems, StepUp stepUp, CurrentUser user, AuditLog audit,
            AuditActor actor, Clock clock) {
        this.store = store;
        this.systems = systems;
        this.stepUp = stepUp;
        this.user = user;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    /** The newest lists, newest first. */
    @Transactional(readOnly = true)
    public List<ListView> lists() {
        Map<UUID, RegisterSystems.SystemView> named = new HashMap<>();
        systems.list().forEach(system -> named.put(system.id(), system));
        return store.newest(SHOWN).stream().map(list -> view(list, named)).toList();
    }

    /**
     * Approves the list waiting for approval. Checks, in order: a fresh sign-in ({@code 403 step-up-required}), the list
     * ({@code 404}), and that it is the DRAFT waiting ({@code 409 list-not-draft}, audited as a refusal).
     */
    @Transactional
    public ListView approve(UUID listId) {
        stepUp.require(APPROVE_INTENT);
        store.lock();
        DisposalStore.ListRow list = store.find(listId).orElseThrow(ResourceNotFoundException::new);
        if (list.status() != ListStatus.DRAFT) {
            audit.recordIndependently(approval(AuditOutcome.REFUSED, listId).with(DetailKey.REASON, "list-not-draft"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "list-not-draft",
                    "This disposal list is no longer waiting for approval: it was replaced by a newer list, or it was "
                            + "approved already. Read the lists again.");
        }
        store.approve(listId, user.id(), clock.instant());
        audit.record(approval(AuditOutcome.SUCCESS, listId));
        Map<UUID, RegisterSystems.SystemView> named = new HashMap<>();
        systems.list().forEach(system -> named.put(system.id(), system));
        return view(store.find(listId).orElseThrow(), named);
    }

    private AuditEvent approval(AuditOutcome outcome, UUID listId) {
        return actor.attribute(AuditEvent.of(AuditAction.DISPOSAL_APPROVED, outcome))
                .with(DetailKey.DISPOSAL_LIST_ID, listId);
    }

    private ListView view(DisposalStore.ListRow list, Map<UUID, RegisterSystems.SystemView> named) {
        List<ItemView> items = store.items(list.id()).stream().map(item -> {
            RegisterSystems.SystemView system = item.systemId() == null ? null : named.get(item.systemId());
            return new ItemView(item.kind(), item.targetId(), item.systemId(),
                    system == null ? null : system.systemId(), system == null ? null : system.name(), item.cycle(),
                    item.reason(), item.entries());
        }).toList();
        return new ListView(list.id(), list.status(), list.computedOn(), list.createdAt(), list.cutoff(),
                list.approvedBy(), list.approvedAt(), list.executedAt(), items);
    }
}
