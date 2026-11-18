package sg.annualva.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The key signals of the application (IM8 lm-16, ADR 0060), the meters the README's runbook gives an alert threshold and an
 * operator response for. Every counter is registered when the application starts, so a scrape shows it at zero before the
 * first event and an alert on its rate never has a missing series to explain. A tag is a value of a closed enum, never text
 * from a request or a report, and a meter carries no id, so nothing here can put report content in a metric (lm-19).
 *
 * <p>Prometheus writes a name with underscores and a counter with {@code _total}: {@code annualva.audit.write.failures}
 * is {@code annualva_audit_write_failures_total}.
 */
@Component
public class KeySignals {

    /** A VA Submission, or a System with none, flagged Overdue and not yet finished; tag {@code kind}. A gauge. */
    public static final String OVERDUE = "annualva.submissions.overdue";
    /** A scanner or store call that failed for a Scan Report file or the Register workbook; the attempt is made again. */
    public static final String SCAN_FAILURES = "annualva.scan.failures";
    /** A file, or a workbook, given up on because every scan attempt timed out or failed. */
    public static final String SCAN_TIMEOUTS = "annualva.scan.timeouts";
    /** A mail the relay did not take or the outbox could not settle; tag {@code kind} ({@link MailFailure}). */
    public static final String MAIL_FAILURES = "annualva.mail.failures";
    /** A sensitive action refused for want of a fresh sign-in, or a step-up round trip that did not prove one; tag {@code kind}. */
    public static final String STEP_UP_REFUSALS = "annualva.stepup.refusals";
    /** An audit entry that could not be written. The action it described is not done, or the refusal it recorded is unrecorded. */
    public static final String AUDIT_WRITE_FAILURES = "annualva.audit.write.failures";

    private static final Logger log = LoggerFactory.getLogger(KeySignals.class);
    private static final String KIND = "kind";

    /** What went wrong with a mail. {@link #ADDRESS_REJECTED} is the recipient's mailbox, the others are ours or the relay's. */
    public enum MailFailure {
        /** The relay could not be reached or refused the hand-over; the mail is tried again later. */
        SEND_FAILED,
        /** The mail was given up on: its last attempt failed, or a dispatcher stopped after it. */
        ABANDONED,
        /** The relay answered and the answer could not be written, so the mail may be sent twice. */
        RECORD_FAILED,
        /** A value of the mail broke the allow-list; it is never sent. */
        REFUSED,
        /** The relay refused the recipient's address (SMO Unreachable). */
        ADDRESS_REJECTED
    }

    /** The two ways a step-up is refused. */
    public enum StepUpRefusal {
        /** A sensitive action came without a sign-in inside the freshness window. */
        REQUIRED,
        /** A step-up round trip came back and did not prove a fresh sign-in of the same person. */
        FAILED
    }

    private final MeterRegistry registry;
    private final Counter scanFailures;
    private final Counter scanTimeouts;
    private final Counter auditWriteFailures;
    private final Map<MailFailure, Counter> mailFailures = new EnumMap<>(MailFailure.class);
    private final Map<StepUpRefusal, Counter> stepUpRefusals = new EnumMap<>(StepUpRefusal.class);

    KeySignals(MeterRegistry registry) {
        this.registry = registry;
        scanFailures = Counter.builder(SCAN_FAILURES)
                .description("Scanner or file store calls that failed during a malware scan").register(registry);
        scanTimeouts = Counter.builder(SCAN_TIMEOUTS)
                .description("Files given up on after every malware scan attempt timed out or failed").register(registry);
        auditWriteFailures = Counter.builder(AUDIT_WRITE_FAILURES)
                .description("Audit entries that could not be written").register(registry);
        for (MailFailure kind : MailFailure.values()) {
            mailFailures.put(kind, Counter.builder(MAIL_FAILURES).tag(KIND, tag(kind))
                    .description("Mails the relay did not take or the outbox could not settle").register(registry));
        }
        for (StepUpRefusal kind : StepUpRefusal.values()) {
            stepUpRefusals.put(kind, Counter.builder(STEP_UP_REFUSALS).tag(KIND, tag(kind))
                    .description("Step-ups refused").register(registry));
        }
    }

    private static String tag(Enum<?> kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    public void scanFailed() {
        scanFailures.increment();
    }

    public void scanTimedOut() {
        scanTimeouts.increment();
    }

    public void auditWriteFailed() {
        auditWriteFailures.increment();
    }

    public void mailFailed(MailFailure kind) {
        mailFailures.get(kind).increment();
    }

    /** {@code count} mails at once, as a dispatcher gives up on every claim whose dispatcher stopped. */
    public void mailFailed(MailFailure kind, int count) {
        mailFailures.get(kind).increment(count);
    }

    public void stepUpRefused(StepUpRefusal kind) {
        stepUpRefusals.get(kind).increment();
    }

    /**
     * Registers the Overdue gauge for one kind of Overdue item; its value is read from the supplier at each scrape. A
     * supplier that fails reads as not-a-number, with a log line that names the kind and no error text, so a database that
     * is down shows as a gap in the series and not as zero overdue.
     *
     * @param kind a fixed word: {@code submission} or {@code not_started}
     */
    public void overdueGauge(String kind, IntSupplier count) {
        Gauge.builder(OVERDUE, () -> read(kind, count)).tag(KIND, kind)
                .description("VA Submissions, and Systems with none, flagged Overdue and not yet finished")
                .register(registry);
    }

    private static double read(String kind, IntSupplier count) {
        try {
            return count.getAsInt();
        } catch (RuntimeException failure) {
            log.atWarn().setMessage("The overdue count could not be read")
                    .addKeyValue("event.action", "overdue-gauge-failed").addKeyValue("labels.kind", kind)
                    .addKeyValue("error.type", failure.getClass().getSimpleName()).log();
            return Double.NaN;
        }
    }
}
