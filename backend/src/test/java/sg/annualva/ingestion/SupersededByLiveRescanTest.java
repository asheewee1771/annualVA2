package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Ticket 68: whether a rescan that is still there supersedes a file is the API's to say ({@code supersededByLiveRescan}),
 * by the one definition of a live report file (ADR 0061 item 8), rather than the screen's to infer from a deleted rescan
 * being absent from the list. It is what decides whether the file can be deleted (ADR 0061 item 1), also when the
 * deletion waits for a settlement that supersedes the file. The race is driven through the API and the scan job; the
 * database is reached, as {@code DisposalGuardTest} reaches it, only to hold the settlement at the right moment and to
 * see who waits for whom.
 */
class SupersededByLiveRescanTest extends DeleteSeam1 {

    /** The advisory lock the held settlement waits for, which this test holds. */
    private static final long HOLD = 6868L;

    /** Once the settlement has superseded the file, holding its row, it waits for the lock this test holds. */
    private static final String HOLD_FUNCTION = "CREATE FUNCTION test_hold_supersede() RETURNS trigger LANGUAGE plpgsql "
            + "AS $$ BEGIN PERFORM pg_advisory_xact_lock(" + HOLD + "); RETURN NEW; END $$";
    private static final String HOLD_TRIGGER = "CREATE TRIGGER test_hold_supersede AFTER UPDATE OF superseded_by "
            + "ON report_file FOR EACH ROW WHEN (OLD.superseded_by IS NULL AND NEW.superseded_by IS NOT NULL) "
            + "EXECUTE FUNCTION test_hold_supersede()";
    private static final String DROP_TRIGGER = "DROP TRIGGER IF EXISTS test_hold_supersede ON report_file";
    private static final String DROP_FUNCTION = "DROP FUNCTION IF EXISTS test_hold_supersede()";

    /** Takes the lock for this transaction; this connection's process id. */
    private static final String TAKE_HOLD = "SELECT pg_backend_pid() FROM (SELECT pg_advisory_xact_lock(" + HOLD + ")) l";

    /** The sessions waiting for a lock this one holds. */
    private static final String BLOCKED_BY = "SELECT pid FROM pg_stat_activity WHERE :blocker = ANY(pg_blocking_pids(pid))";

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient application;

    @Test
    void aFileSaysWhetherARescanThatIsStillThereSupersedesIt() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        assertThat(status(draft.smo(), sub, first).get("supersededByLiveRescan").asBoolean()).as("never replaced")
                .isFalse();

        String rescan = replacedAndSettled(draft, first, "rescan.json", firstScan()).get("id").asString();

        assertThat(status(draft.smo(), sub, first).get("supersededByLiveRescan").asBoolean()).isTrue();
        assertThat(listed(draft, first).get("supersededByLiveRescan").asBoolean()).as("the list says the same").isTrue();
        assertThat(status(draft.smo(), sub, rescan).get("supersededByLiveRescan").asBoolean()).isFalse();
        assertConflict(deleteFile(draft.smo(), sub, first), "file-not-deletable");

        fileDeleted(draft.smo(), sub, rescan);

        JsonNode replaced = listed(draft, first);
        assertThat(replaced.get("supersededBy").asString()).as("it stays superseded").isEqualTo(rescan);
        assertThat(replaced.get("supersededByLiveRescan").asBoolean()).as("by a rescan that was deleted").isFalse();
        fileDeleted(draft.smo(), sub, first);
    }

    /**
     * The race the deletion is written for (ADR 0061 items 1 and 8): the scan job's settlement of a rescan supersedes the
     * file and holds its row, the deletion waits for that row, and the settlement commits. The deletion must then see the
     * live rescan and refuse, never delete a file a live rescan supersedes. A trigger this test adds holds the settlement
     * just after its supersede, until the deletion is seen waiting for it.
     */
    @Test
    void aDeletionThatWaitedForTheSupersedeOfItsFileIsRefused() throws Exception {
        Draft draft = draft();
        String sub = draft.submissionId();
        String first = firstScanOf(draft);
        String rescan = replaced(draft, first, "rescan.json", firstScan()).get("id").asString();
        pass();
        assertThat(status(draft.smo(), sub, rescan).get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        ExecutorService threads = Executors.newFixedThreadPool(3);
        CountDownLatch release = new CountDownLatch(1);
        try {
            application.sql(HOLD_FUNCTION).update();
            application.sql(HOLD_TRIGGER).update();
            CompletableFuture<Integer> hold = new CompletableFuture<>();
            threads.submit(() -> transaction.executeWithoutResult(status -> {
                hold.complete(application.sql(TAKE_HOLD).query(Integer.class).single());
                awaitQuietly(release);
            }));
            int holder = hold.get(30, TimeUnit.SECONDS);

            CompletableFuture<Void> settlement = CompletableFuture.runAsync(this::pass, threads);
            int settling = blockedBy(holder);
            CompletableFuture<ApiClient.Response> deletion =
                    CompletableFuture.supplyAsync(() -> deleteFile(draft.smo(), sub, first), threads);
            blockedBy(settling);
            release.countDown();

            settlement.get(30, TimeUnit.SECONDS);
            assertConflict(deletion.get(30, TimeUnit.SECONDS), "file-not-deletable");
        } finally {
            release.countDown();
            threads.shutdown();
            assertThat(threads.awaitTermination(30, TimeUnit.SECONDS)).as("the threads finished").isTrue();
            application.sql(DROP_TRIGGER).update();
            application.sql(DROP_FUNCTION).update();
        }
        JsonNode file = status(draft.smo(), sub, first);
        assertThat(file.get("supersededBy").asString()).as("the settlement superseded it").isEqualTo(rescan);
        assertThat(file.get("supersededByLiveRescan").asBoolean()).isTrue();
        assertThat(fileIds(draft.smo(), sub)).containsExactly(first, rescan);
    }

    /** The process id of the session waiting for a lock the one given holds, once there is one. */
    private int blockedBy(int blocker) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        List<Integer> waiting = List.of();
        while (waiting.isEmpty()) {
            assertThat(System.nanoTime()).as("a session waits for " + blocker).isLessThan(until);
            Thread.sleep(50);
            waiting = application.sql(BLOCKED_BY).param("blocker", blocker).query(Integer.class).list();
        }
        return waiting.get(0);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The file as the VA Submission's list gives it. */
    private static JsonNode listed(Draft draft, String fileId) {
        for (JsonNode file : ApiClient.json(draft.smo().get(filesOf(draft.submissionId())))) {
            if (file.get("id").asString().equals(fileId)) {
                return file;
            }
        }
        throw new AssertionError("not listed: " + fileId);
    }
}
