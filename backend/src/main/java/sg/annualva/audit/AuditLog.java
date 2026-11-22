package sg.annualva.audit;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.web.CorrelationIdFilter;
import sg.annualva.web.Page;

/**
 * The one way to write to and read the audit log. An entry is stored with the time from the {@code Clock} bean and the
 * request's correlation id, and once stored it can be neither changed nor removed (the table refuses it). After the
 * transaction that wrote it commits, the same entry goes out as an ECS event on the {@value #LOGGER_NAME} logger
 * (lm-15), so a rolled-back entry is never announced.
 *
 * <p>Two ways to write, because two things must hold:
 * <ul>
 *   <li>{@link #record}: in the caller's transaction, which must exist. The entry commits with the action it
 *       describes and rolls back with it, so the log never says something happened that did not.
 *   <li>{@link #recordIndependently}: in a transaction of its own, committed whatever the caller's transaction does
 *       afterwards. For a refusal, a sign-in and anything else that no domain transaction owns.
 * </ul>
 *
 * <p>An entry that cannot be written is counted once as an audit write failure (IM8 lm-16, ADR 0060 item 5) and the
 * failure is thrown on: for {@link #record}, an insert the database refused; for {@link #recordIndependently}, anything
 * that fails in its own transaction, from beginning it (the database is down, no connection is free) to the commit.
 */
@Service
public class AuditLog {

    /** The logger the entries are emitted on, apart from the application's own, so operators can route it. */
    public static final String LOGGER_NAME = "annualva.audit";

    private static final Logger events = LoggerFactory.getLogger(LOGGER_NAME);

    private final AuditStore store;
    private final Clock clock;
    private final AuditCaller caller;
    private final KeySignals signals;
    private final TransactionTemplate independent;

    AuditLog(AuditStore store, Clock clock, AuditCaller caller, KeySignals signals,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.clock = clock;
        this.caller = caller;
        this.signals = signals;
        this.independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Writes in the caller's transaction; fails when there is none, so an entry cannot be written by accident without one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        write(event);
    }

    /**
     * Writes in a new transaction that commits on its own, even if the caller's transaction later rolls back. The
     * transaction is begun here, not by a proxy, so a failure to begin it or to commit it is counted like a refused insert.
     */
    public void recordIndependently(AuditEvent event) {
        try {
            independent.executeWithoutResult(status -> insert(event));
        } catch (RuntimeException failure) {
            signals.auditWriteFailed();
            throw failure;
        }
    }

    /** Newest first; {@code page} counts from zero. */
    public Page<AuditEntry> entries(AuditFilter filter, int page, int size) {
        return new Page<>(store.page(filter, size, (long) page * size), page, size, store.count(filter));
    }

    /**
     * A page of entries for a reader, narrowed by {@code filter} (the query's, with the reader's scope), and the read itself
     * recorded as an {@code AUDIT_LOG_VIEWED} entry by the reader, in one transaction: a read the log cannot record is not
     * answered (Q11, IM8 lm-4, ADR 0057).
     */
    @Transactional
    public Page<AuditEntry> read(AuditQuery query, AuditFilter filter, int size) {
        Page<AuditEntry> entries = entries(filter, query.page(), size);
        write(caller.attribute(query.viewed()));
        return entries;
    }

    /** In the caller's transaction. */
    private void write(AuditEvent event) {
        try {
            insert(event);
        } catch (RuntimeException failure) {
            // Counted, then thrown on as before: the caller's transaction rolls back, so the action is not done unrecorded.
            signals.auditWriteFailed();
            throw failure;
        }
    }

    private void insert(AuditEvent event) {
        AuditEntry entry = store.insert(event, clock.instant(), CorrelationIdFilter.current());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emit(entry);
            }
        });
    }

    private static void emit(AuditEntry entry) {
        LoggingEventBuilder event = events.atLevel(entry.outcome() == AuditOutcome.SUCCESS ? Level.INFO : Level.WARN)
                .setMessage("Audit entry")
                .addKeyValue("event.id", entry.id().toString())
                .addKeyValue("event.action", entry.action().ecs())
                .addKeyValue("event.category", entry.action().category())
                .addKeyValue("event.outcome", entry.outcome().ecs());
        if (entry.actorId() != null) {
            event = event.addKeyValue("user.id", entry.actorId().toString());
        }
        if (entry.actorRole() != null) {
            event = event.addKeyValue("user.roles", entry.actorRole());
        }
        if (entry.systemId() != null) {
            event = event.addKeyValue("labels.system_id", entry.systemId().toString());
        }
        if (entry.submissionId() != null) {
            event = event.addKeyValue("labels.submission_id", entry.submissionId().toString());
        }
        if (entry.findingId() != null) {
            event = event.addKeyValue("labels.finding_id", entry.findingId().toString());
        }
        for (var detail : entry.detail().entrySet()) {
            String key = DetailKey.REASON.json().equals(detail.getKey()) ? "event.reason" : "labels." + detail.getKey();
            event = event.addKeyValue(key, detail.getValue());
        }
        event.log();
    }
}
