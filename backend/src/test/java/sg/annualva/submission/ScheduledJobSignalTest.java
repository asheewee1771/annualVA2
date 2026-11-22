package sg.annualva.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static sg.annualva.harness.Metrics.job;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import sg.annualva.harness.Metrics;

/**
 * IM8 lm-16 (ticket 67, ADR 0060 item 8): every scheduled job records when it last completed a run, on a series of its own
 * on {@code /actuator/prometheus}, so a job that has stopped shows as a time that no longer moves. The jobs are read from
 * the running application (every {@code @Scheduled} method of its beans), not listed here, and each is run on its own
 * bean as the scheduler runs it. The time is the application clock's; a run that fails leaves it where it was, and a run
 * with nothing to do still moves it.
 */
class ScheduledJobSignalTest extends SubmissionSeam1 {

    @Autowired
    private ApplicationContext context;

    @AfterEach
    void faultOff() {
        arrange.exportListingFault("off");
        arrange.auditFault("off");
    }

    /** The scheduler is switched on only by {@code annualva.scheduling.enabled}, so in tests a job runs only when run. */
    @Test
    void theSchedulerIsOffInTests() {
        assertThat(context.getBeanNamesForType(ScheduledTaskHolder.class)).as("no scheduled-annotation processor")
                .isEmpty();
    }

    @Test
    void everyScheduledJobMovesASeriesOfItsOwnToTheTimeItCompletedEvenWithNothingToDo() {
        List<String> jobs = arrange.scheduledJobs();
        assertThat(jobs).as("the jobs the ticket names, among every one in the code")
                .contains("OverdueSweepJob.run", "DeadlineReminderJob.run", "MailDispatchJob.run", "ScanPollerJob.run");

        Map<String, Set<String>> movedBy = new HashMap<>();
        for (String name : jobs) {
            clock.advance(Duration.ofMinutes(7));
            Metrics before = metrics();

            var ran = arrange.scheduledJob(name);
            Metrics after = metrics();

            assertThat(ran.status()).as(name + ": " + ran.body()).isEqualTo(200);
            Map<String, Double> moved = new HashMap<>(after.jobs());
            moved.entrySet().removeIf(series -> series.getValue().equals(before.jobs().get(series.getKey())));
            assertThat(moved).as("%s moves a series", name).isNotEmpty();
            assertThat(moved.values()).as("%s completed now, by the application clock", name)
                    .allSatisfy(at -> assertThat(at).isCloseTo(seconds(), within(0.001)));
            movedBy.put(name, moved.keySet());
        }
        // A job may move another's series as well (the daily retention listing runs the executor's pass first), but
        // each must have one of its own: match each job to a series it moved, the jobs that moved fewest first.
        Set<String> owned = new HashSet<>();
        movedBy.entrySet().stream().sorted(Comparator.comparingInt(moved -> moved.getValue().size()))
                .forEach(moved -> assertThat(moved.getValue().stream().filter(owned::add).findFirst())
                        .as("%s has a series of its own", moved.getKey()).isPresent());
        assertThat(metrics().jobs().keySet()).as("a series for every job, and no series without one")
                .containsExactlyInAnyOrderElementsOf(owned);
    }

    @Test
    void aRunThatFailsLeavesTheTimeWhereItWasAndTheNextCompletedRunMovesIt() {
        registered(listed("SYS-J1", "Job one", SMO_A, CM, "Q3"));
        arrange.scheduledJob("OverdueSweepJob.run");
        Metrics before = metrics();
        // Q3's Submission Window ended on 15 August 2027, so SYS-J1, with no VA Submission, is flagged Not Started.
        today("2027-08-16");
        arrange.auditFault("SUBMISSION_OVERDUE");

        var failed = arrange.scheduledJob("OverdueSweepJob.run");
        Metrics afterFailure = metrics();
        arrange.auditFault("off");
        clock.advance(Duration.ofMinutes(5));
        var completed = arrange.scheduledJob("OverdueSweepJob.run");

        assertThat(failed.status()).as("the sweep's write failed and its pass rolled back").isEqualTo(500);
        assertThat(afterFailure.value(job("overdue_sweep"))).as("a failed run is not a completed one")
                .isEqualTo(before.value(job("overdue_sweep")));
        assertThat(completed.status()).isEqualTo(200);
        assertThat(metrics().value(job("overdue_sweep"))).isCloseTo(seconds(), within(0.001));
    }

    /**
     * The export job catches a listing that fails and returns, so the run answers as any other; it is still not a completed
     * one, since it looked at none of its work.
     */
    @Test
    void anExportRunWhoseListingsFailedIsNotACompletedOneThoughItReturns() {
        arrange.scheduledJob("ExportWorkerJob.run");
        Metrics before = metrics();
        clock.advance(Duration.ofMinutes(5));
        arrange.exportListingFault("on");

        var failed = arrange.scheduledJob("ExportWorkerJob.run");
        Metrics afterFailure = metrics();
        arrange.exportListingFault("off");
        clock.advance(Duration.ofMinutes(5));
        var completed = arrange.scheduledJob("ExportWorkerJob.run");

        assertThat(failed.status()).as("the job caught the failure itself").isEqualTo(200);
        assertThat(afterFailure.value(job("export_worker"))).as("a pass that read no listing is not completed")
                .isEqualTo(before.value(job("export_worker")));
        assertThat(completed.status()).isEqualTo(200);
        assertThat(metrics().value(job("export_worker"))).isCloseTo(seconds(), within(0.001));
    }

    private double seconds() {
        return clock.instant().toEpochMilli() / 1000.0;
    }
}
