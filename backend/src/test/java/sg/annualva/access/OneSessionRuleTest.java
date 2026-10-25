package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;

/**
 * IM8 as-11, ADR 0008: one session per person, however many sessions the store holds. Each sign-in ends the person's
 * older session, and a step-up never ends the session it is made in. Ticket 50 found that the session store read a
 * person's sessions back split in parts once it held many rows of theirs (its query had no order and its reader groups
 * adjacent rows), so an older session's "ended" mark was lost, it stayed live, and the person's next step-up ended the
 * current session instead: the 401 after a step-up seen in tickets 09, 13 and 46 (ADR 0057).
 */
class OneSessionRuleTest extends Seam1Test {

    private static final String START = "/auth/step-up?intent=probe-sensitive&returnTo=/va-poc";

    @Autowired
    private JdbcClient database;

    @Autowired
    private JdbcIndexedSessionRepository sessionStore;

    private Instant before;

    @BeforeEach
    void rememberTheClock() {
        before = clock.instant();
    }

    @AfterEach
    void putTheClockBack() {
        clock.set(before);
    }

    /**
     * The test below meets the defect only when PostgreSQL's planner joins by hash; this one does not depend on the plan:
     * the store the application runs with lists a person's sessions in order, whichever customizer ran last.
     */
    @Test
    void theStoreListsAPersonsSessionsOrderedBySession() {
        assertThat((String) ReflectionTestUtils.getField(sessionStore, "listSessionsByPrincipalNameQuery"))
                .contains("ORDER BY S.SESSION_ID");
    }

    @Test
    void everySignInEndsTheOlderSessionAndEveryStepUpKeepsItsOwnAsTheStoreFillsUp() {
        TestIdentity person = TestIdentity.of("af-one-session", "one.session@ci.annualva.test", "One Session");
        for (int round = 1; round <= 150; round++) {
            SignIn.signedIn(anotherBrowser(), issuer, person.withAuthTime(clock.instant()));
        }
        // The planner's statistics, as autovacuum keeps them in a long-running store.
        database.sql("ANALYZE spring_session").update();
        database.sql("ANALYZE spring_session_attributes").update();
        ApiClient previous = null;
        for (int round = 1; round <= 10; round++) {
            ApiClient browser = anotherBrowser();
            SignIn.signedIn(browser, issuer, person.withAuthTime(clock.instant()));
            if (previous != null) {
                assertThat(previous.get("/api/probe/whoami").status()).as("round " + round + ": the older session ended")
                        .isEqualTo(401);
            }
            clock.advance(Duration.ofMinutes(6));
            SignIn.stepUp(browser, issuer, START, person.withAuthTime(clock.instant()));
            assertThat(browser.get("/api/probe/whoami").status()).as("round " + round + ": the step-up kept its session")
                    .isEqualTo(200);
            previous = browser;
        }
    }
}
