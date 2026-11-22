package sg.annualva.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sg.annualva.monitoring.KeySignals;
import sg.annualva.monitoring.KeySignals.Job;

/**
 * Runs the retention job on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012): the executor every
 * few minutes, so an approved list is disposed of soon after the approval, and the listing once a day, early in the
 * Singapore morning.
 * Seam 1 tests leave it off, move the clock and run this bean through the harness's
 * {@code ScheduledJobRuns}, as the scheduler would.
 */
@Component
class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final DisposalExecutor executor;
    private final DisposalPlanner planner;
    private final KeySignals signals;

    RetentionJob(DisposalExecutor executor, DisposalPlanner planner, KeySignals signals) {
        this.executor = executor;
        this.planner = planner;
        this.signals = signals;
    }

    /**
     * Between passes, not at a fixed rate: a slow pass never overlaps the next. Recorded as completed (ticket 67) when the
     * approved list was disposed of or none was waiting; a disposal that failed ({@code disposal-failed}) is not.
     */
    @Scheduled(fixedDelayString = "${annualva.retention.execute-interval:5m}")
    void execute() {
        if (executor.execute()) {
            signals.jobCompleted(Job.RETENTION_EXECUTE);
        }
    }

    /**
     * The executor's pass first, recorded as the executor's own when it completes, then the listing, recorded as completed
     * (ticket 67) only when it was written: a listing that failed is logged here and not recorded.
     */
    @Scheduled(cron = "${annualva.retention.list-cron:0 30 2 * * *}", zone = "${annualva.time-zone}")
    void list() {
        execute();
        try {
            planner.plan();
            signals.jobCompleted(Job.RETENTION_LIST);
        } catch (RuntimeException e) {
            // The type only: a database message could quote a key (lm-19). The next day's pass lists again.
            log.atWarn().setMessage("The retention job could not list what is due")
                    .addKeyValue("event.action", "disposal-list-failed")
                    .addKeyValue("error.type", e.getClass().getName()).log();
        }
    }
}
