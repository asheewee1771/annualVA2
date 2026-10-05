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

/**
 * Read access to the audit log (story 84), for the VA POCs only. Cyber Managers read the history of the
 * submissions they reviewed and Auditors the log within their scope in later tickets. There is no write, update or
 * delete endpoint: the log changes only by the application recording something.
 */
@RestController
public class AuditController {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private final AuditLog auditLog;
    private final ZoneId businessZone;

    AuditController(AuditLog auditLog, AnnualVaProperties properties) {
        this.auditLog = auditLog;
        this.businessZone = ZoneId.of(properties.timeZone());
    }

    /**
     * Newest first. {@code from} and {@code to} are business dates in {@code Asia/Singapore}, both inclusive.
     */
    @GetMapping("/api/audit-entries")
    @PreAuthorize("hasRole('VA_POC')")
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
        var filter = new AuditFilter(actor, action, system, submission,
                from == null ? null : from.atStartOfDay(businessZone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(businessZone).toInstant());
        return auditLog.entries(filter, page, size);
    }
}
