package sg.annualva.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.AuditEntries;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * What the audit tests share. One database and one application serve the whole suite, so each test works on a
 * business day of its own (the clock is moved there and put back afterwards) and reads the log narrowed to that day:
 * what other tests wrote never shows. A test reads the log as the second VA POC, through the HTTP API like anyone.
 */
abstract class AuditSeam1 extends Seam1Test {

    static final String POC_TWO_EMAIL = "poc.two@ci.annualva.test";
    private static final AtomicInteger NEXT_DAY = new AtomicInteger();
    private static final LocalDate FIRST_DAY = LocalDate.of(2031, 1, 1);

    private Instant before;
    private LocalDate today;

    @BeforeEach
    void moveToADayOfItsOwn() {
        before = clock.instant();
        today = FIRST_DAY.plusDays(NEXT_DAY.getAndIncrement());
        clock.set(today.atTime(1, 0).toInstant(java.time.ZoneOffset.UTC));
    }

    @AfterEach
    void putTheClockBack() {
        clock.set(before);
    }

    /** The business date (Asia/Singapore) the test is working on; 09:00 there is 01:00 UTC. */
    protected String today() {
        return today.toString();
    }

    /** Signs a second VA POC in, in a browser of its own, to read the log. */
    protected ApiClient reader() {
        ApiClient reader = anotherBrowser();
        SignIn.signedIn(reader, issuer, TestIdentity.of("af-5002", POC_TWO_EMAIL, "POC Two"));
        return reader;
    }

    /** Today's entries, newest first, matching the extra query (for example {@code action=SIGN_IN}). */
    protected List<JsonNode> entries(ApiClient reader, String query) {
        return allPages(reader, "from=" + today() + "&to=" + today() + (query.isEmpty() ? "" : "&" + query));
    }

    /** Every page of an answer, so a test never depends on how many entries the other tests left. */
    protected static List<JsonNode> allPages(ApiClient reader, String query) {
        return AuditEntries.all(reader, query);
    }

    protected static List<String> actions(List<JsonNode> entries) {
        return entries.stream().map(entry -> entry.get("action").asString()).toList();
    }
}
