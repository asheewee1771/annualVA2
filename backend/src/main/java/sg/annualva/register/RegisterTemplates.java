package sg.annualva.register;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;

/**
 * Hands a VA POC the System Register template and records that it did (IM8 lm-4). The entry is written in the same
 * transaction as the work, so it exists exactly when a file was produced. The template names no System and holds no
 * data, so there is no ownership to check (ADR 0013) and the entry carries no detail.
 */
@Service
public class RegisterTemplates {

    /** The file name the browser saves the template under. */
    static final String FILE_NAME = "system-register-template.xlsx";

    /** @param content the {@code .xlsx} file's bytes */
    public record Template(String fileName, byte[] content) {}

    private final RegisterTemplate template;
    private final AuditLog audit;
    private final AuditActor actor;

    RegisterTemplates(Clock clock, AuditLog audit, AuditActor actor) {
        this.template = new RegisterTemplate(clock);
        this.audit = audit;
        this.actor = actor;
    }

    @Transactional
    public Template download() {
        byte[] content = template.build();
        audit.record(actor.attribute(AuditEvent.of(AuditAction.REGISTER_TEMPLATE_DOWNLOADED, AuditOutcome.SUCCESS)));
        return new Template(FILE_NAME, content);
    }
}
