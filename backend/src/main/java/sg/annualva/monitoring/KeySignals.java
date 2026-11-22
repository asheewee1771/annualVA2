package sg.annualva.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The key signals of the application (IM8 lm-16, ADR 0060), the meters the README's runbook gives an alert threshold and an
 * operator response for. Every counter is registered when the application starts, so a scrape shows it at zero before the
 * first event and an alert on its rate never has a missing series to explain. A tag is a value of a closed enum, never text
 * from a request or a report, and a meter carries no id, so nothing here can put report content in a metric (lm-19).
 *
 * <p>Prometheus writes a name with underscores and a counter with {@code _total}: {@code annualva.audit.write.failures}
 * is {@code annualva_audit_write_failures_total}.
 *
 * <p>Nothing here reads the database: a scrape only reads values held in memory, so a slow or unreachable database cannot
 * hold the scrape past the scraper's timeout. The Overdue gauges hold what the last committed sweep counted, and the job
 * gauges when each scheduled job last completed a run (ticket 67), read from the {@code Clock} bean.
 */
@Component
public class KeySignals {

    /** A VA Submission, or a System with none, flagged Overdue and not yet finished; tag {@code kind}. A gauge. */
    private static final String OVERDUE = "annualva.submissions.overdue";
    /** A scanner or store call that failed for a Scan Report file or the Register workbook; the attempt is made again. */
    private static final String SCAN_FAILURES = "annualva.scan.failures";
    /** A file, or a workbook, given up on because every scan attempt timed out or failed. */
    private static final String SCAN_TIMEOUTS = "annualva.scan.timeouts";
    /** A mail the relay did not take or the outbox could not settle; tag {@code kind} ({@link MailFailure}). */
    private static final String MAIL_FAILURES = "annualva.mail.failures";
    /** A sensitive action refused for want of a fresh sign-in, or a step-up round trip that did not prove one; tag {@code kind}. */
    private static final String STEP_UP_REFUSALS = "annualva.stepup.refusals";
    /** An audit entry that could not be written. The action it described is not done, or the refusal it recorded is unrecorded. */
    private static final String AUDIT_WRITE_FAILURES = "annualva.audit.write.failures";
    /** When a scheduled job last completed a run, in seconds since the epoch; tag {@code kind} ({@link Job}). A gauge. */
    private static final String JOB_LAST_COMPLETED = "annualva.job.last.completed";

    private static final String KIND = "kind";

    /**
     * Every scheduled job, one per {@code @Scheduled} method ({@code ScheduledJobSignalTest} fails when one has no value
     * here). Prometheus sets a {@code job} label of its own, the scrape's, so the tag is {@code kind}, as on the others.
     */
    public enum Job {
        /** {@code ScanPollerJob}: the malware scan and parse of uploaded files. */
        SCAN_POLLER,
        /** {@code MailDispatchJob}: the mail outbox. */
        MAIL_DISPATCH,
        /** {@code ExportWorkerJob}: the queued exports. */
        EXPORT_WORKER,
        /** {@code OverdueSweepJob}: the Overdue sweep. */
        OVERDUE_SWEEP,
        /** {@code DeadlineReminderJob}: the deadline reminders. */
        DEADLINE_REMINDERS,
        /** {@code CycleLifecycleJob}: opening and closing VA Cycles. */
        CYCLE_LIFECYCLE,
        /** {@code ExemptionLapseJob}: the lapse of conditional Exemptions. */
        EXEMPTION_LAPSE,
        /** {@code RoleLapseJob}: role lapses and returns. */
        ROLE_LAPSE,
        /** {@code RetentionJob.execute}: disposing of an approved disposal list. */
        RETENTION_EXECUTE,
        /** {@code RetentionJob.list}: listing what is due for disposal. */
        RETENTION_LIST
    }

    /** What is Overdue: a VA Submission, or an active System with none in that VA Cycle (Not Started). */
    public enum Overdue {
        /** VA Submissions flagged Overdue and not yet in a final state. */
        SUBMISSION,
        /** Active Systems flagged Overdue as Not Started that still have no VA Submission in that VA Cycle. */
        NOT_STARTED
    }

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

    private final Counter scanFailures;
    private final Counter scanTimeouts;
    private final Counter auditWriteFailures;
    private final Map<MailFailure, Counter> mailFailures = new EnumMap<>(MailFailure.class);
    private final Map<StepUpRefusal, Counter> stepUpRefusals = new EnumMap<>(StepUpRefusal.class);
    /** The bits of each gauge's double; not-a-number until the first sweep has committed. */
    private final Map<Overdue, AtomicLong> overdue = new EnumMap<>(Overdue.class);
    /** Each job's last completed run, in milliseconds since the epoch; the start of the application until its first. */
    private final Map<Job, AtomicLong> lastCompleted = new EnumMap<>(Job.class);
    private final Clock clock;

    KeySignals(MeterRegistry registry, Clock clock) {
        this.clock = clock;
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
        for (Overdue kind : Overdue.values()) {
            AtomicLong bits = new AtomicLong(Double.doubleToLongBits(Double.NaN));
            overdue.put(kind, bits);
            Gauge.builder(OVERDUE, bits, held -> Double.longBitsToDouble(held.get())).tag(KIND, tag(kind))
                    .description("VA Submissions, and Systems with none, flagged Overdue and not yet finished")
                    .register(registry);
        }
        long started = clock.millis();
        for (Job kind : Job.values()) {
            AtomicLong at = new AtomicLong(started);
            lastCompleted.put(kind, at);
            TimeGauge.builder(JOB_LAST_COMPLETED, at, TimeUnit.MILLISECONDS, AtomicLong::get).tag(KIND, tag(kind))
                    .description("When each scheduled job last completed a run, or the application started if none has")
                    .register(registry);
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
     * What the sweep counted as Overdue, once its transaction has committed. The gauge holds it until the next sweep: a
     * rise is seen at the sweep that flags it, and a fall (a VA Submission finished, a System retired or moved) at the next
     * sweep. Before the first sweep after a start the gauge is not-a-number, not zero.
     */
    public void overdue(Overdue kind, int count) {
        overdue.get(kind).set(Double.doubleToLongBits(count));
    }

    /**
     * A scheduled job completed a run, now by the application clock: its scheduled method calls this as its last step, so
     * a run that throws never reaches it and a run that found nothing to do does. Until a job's first completed run the
     * series holds the time the application started, so a job that never runs after a start is alerted on too.
     */
    public void jobCompleted(Job kind) {
        lastCompleted.get(kind).set(clock.millis());
    }
}
