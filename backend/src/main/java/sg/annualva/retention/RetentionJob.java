package sg.annualva.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the retention job on a schedule when {@code annualva.scheduling.enabled} is true (ADR 0012): the executor every
 * few minutes, so an approved list is disposed of soon after the approval, and the listing once a day, early in the
 * Singapore morning. Seam 1 tests leave it off, move the clock and run a pass themselves.
 */
@Component
@ConditionalOnProperty(name = "annualva.scheduling.enabled", havingValue = "true")
class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final DisposalExecutor executor;
    private final DisposalPlanner planner;

    RetentionJob(DisposalExecutor executor, DisposalPlanner planner) {
        this.executor = executor;
        this.planner = planner;
    }

    /** Between passes, not at a fixed rate: a slow pass never overlaps the next. */
    @Scheduled(fixedDelayString = "${annualva.retention.execute-interval:5m}")
    void execute() {
        executor.execute();
    }

    @Scheduled(cron = "${annualva.retention.list-cron:0 30 2 * * *}", zone = "${annualva.time-zone}")
    void list() {
        executor.execute();
        try {
            planner.plan();
        } catch (RuntimeException e) {
            // The type only: a database message could quote a key (lm-19). The next day's pass lists again.
            log.atWarn().setMessage("The retention job could not list what is due")
                    .addKeyValue("event.action", "disposal-list-failed")
                    .addKeyValue("error.type", e.getClass().getName()).log();
        }
    }
}
