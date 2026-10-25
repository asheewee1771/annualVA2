package sg.annualva.audit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import sg.annualva.config.AnnualVaProperties;
import sg.annualva.web.Page;
import sg.annualva.web.ResourceNotFoundException;

/**
 * Read access to the audit log: a VA POC reads every entry (story 84), a Cyber Manager the history of the VA Submissions
 * they reviewed on Systems still assigned to them (story 85, ADR 0038), with the same filters. Auditors read the log within
 * their scope in a later ticket. Every answered read is itself an {@code AUDIT_LOG_VIEWED} entry (Q11, ADR 0057). There is
 * no write, update or delete endpoint: the log changes only by the application recording something.
 */
@RestController
public class AuditController {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private final AuditLog auditLog;
    private final AuditReaders readers;
    private final AuditCaller caller;
    private final ZoneId businessZone;

    AuditController(AuditLog auditLog, AuditReaders readers, AuditCaller caller, AnnualVaProperties properties) {
        this.auditLog = auditLog;
        this.readers = readers;
        this.caller = caller;
        this.businessZone = ZoneId.of(properties.timeZone());
    }

    /**
     * Newest first. {@code from} and {@code to} are business dates in {@code Asia/Singapore}, both inclusive. A VA
     * Submission outside the caller's read scope answers 404, as one that does not exist (ADR 0013); every other filter
     * only narrows what the caller may read. The read is recorded with what it asked for ({@link #viewed}).
     */
    @GetMapping("/api/audit-entries")
    @PreAuthorize("hasAnyRole('VA_POC','CYBER_MANAGER')")
    public Page<AuditEntry> entries(
            @RequestParam(required = false) @Nullable UUID actor,
            @RequestParam(required = false) @Nullable AuditAction action,
            @RequestParam(required = false) @Nullable UUID system,
            @RequestParam(required = false) @Nullable UUID submission,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The from date is after the to date.");
        }
        AuditReadScope scope = readers.scopeOfCaller();
        if (submission != null && !scope.allows(submission)) {
            throw new ResourceNotFoundException();
        }
        var filter = new AuditFilter(actor, action, system, submission,
                from == null ? null : from.atStartOfDay(businessZone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(businessZone).toInstant(),
                scope.narrowing());
        return auditLog.read(filter, page, size, viewed(actor, action, system, submission, from, to, page));
    }

    /** The entry of a read: by the caller, about the System and VA Submission asked for, with the other filters. */
    private AuditEvent viewed(@Nullable UUID actor, @Nullable AuditAction action, @Nullable UUID system,
            @Nullable UUID submission, @Nullable LocalDate from, @Nullable LocalDate to, int page) {
        AuditEvent viewed = caller.attribute(AuditEvent.of(AuditAction.AUDIT_LOG_VIEWED, AuditOutcome.SUCCESS))
                .with(DetailKey.PAGE, page);
        if (system != null) {
            viewed = viewed.onSystem(system);
        }
        if (submission != null) {
            viewed = viewed.onSubmission(submission);
        }
        if (actor != null) {
            viewed = viewed.with(DetailKey.FILTER_ACTOR, actor);
        }
        if (action != null) {
            viewed = viewed.with(DetailKey.FILTER_ACTION, action.name());
        }
        if (from != null) {
            viewed = viewed.with(DetailKey.FILTER_FROM, from.toString());
        }
        if (to != null) {
            viewed = viewed.with(DetailKey.FILTER_TO, to.toString());
        }
        return viewed;
    }
}
