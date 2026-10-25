package sg.annualva.audit;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One read of the audit log as a reader asked for it: the filters (a null one does not narrow), with {@code from} and
 * {@code to} as business dates, both inclusive, and the page. It is both what the log is narrowed by and what the read is
 * recorded as (Q11, ADR 0057), so the two never disagree.
 */
public record AuditQuery(
        @Nullable UUID actor,
        @Nullable AuditAction action,
        @Nullable UUID system,
        @Nullable UUID submission,
        @Nullable LocalDate from,
        @Nullable LocalDate to,
        int page) {

    /** The filter, with the dates as instants in the business zone and the reader's scope. */
    AuditFilter filter(ZoneId businessZone, @Nullable Set<UUID> within) {
        return new AuditFilter(actor, action, system, submission,
                from == null ? null : from.atStartOfDay(businessZone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(businessZone).toInstant(),
                within);
    }

    /**
     * The read as an entry: every filter asked for as detail (ids, a code and dates), and the page; never about a System or
     * a VA Submission, so a read never lands in their history, and never anything an entry says.
     */
    AuditEvent viewed() {
        AuditEvent viewed = AuditEvent.of(AuditAction.AUDIT_LOG_VIEWED, AuditOutcome.SUCCESS).with(DetailKey.PAGE, page);
        viewed = asked(viewed, DetailKey.FILTER_ACTOR, actor);
        viewed = asked(viewed, DetailKey.FILTER_ACTION, action);
        viewed = asked(viewed, DetailKey.FILTER_SYSTEM, system);
        viewed = asked(viewed, DetailKey.FILTER_SUBMISSION, submission);
        viewed = asked(viewed, DetailKey.FILTER_FROM, from);
        return asked(viewed, DetailKey.FILTER_TO, to);
    }

    /** The filter as a fixed code (an id, an action's name or an ISO date), when it was asked for. */
    private static AuditEvent asked(AuditEvent viewed, DetailKey key, @Nullable Object filter) {
        return filter == null ? viewed : viewed.with(key, filter.toString());
    }
}
