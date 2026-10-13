package sg.annualva.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * Story 19: an SMO/Tech Lead starts the VA Submission for one of their Systems. It is created in Draft with its windows
 * taken from the Quarter, one per System and VA Cycle, whether or not the Quarter has begun, and the caller is recorded
 * as having acted at the submitting step.
 */
class SubmissionStartTest extends SubmissionSeam1 {

    private void registerSystems() {
        registered(
                List.of(listed("SYS-S1", "Start one", SMO_A, CM, "Q3"),
                        listed("SYS-S2", "Start two", SMO_A, CM, "Q4"),
                        listed("SYS-S0", "Start zero", SMO_A, CM, "Q1")),
                List.of());
    }

    @Test
    void startingCreatesADraftWithTheWindowsOfTheQuarterAndTheCallerAtTheSubmittingStep() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);

        var response = start(smo, idOf("SYS-S1"));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode submission = ApiClient.json(response);
        assertThat(response.header("Location")).isEqualTo("/api/submissions/" + submission.get("id").asString());
        assertThat(submission.get("systemId").asString()).isEqualTo(idOf("SYS-S1"));
        assertThat(submission.get("cycle").asInt()).isEqualTo(2027);
        assertThat(submission.get("quarter").asInt()).isEqualTo(3);
        assertThat(submission.get("state").asString()).isEqualTo("DRAFT");
        assertThat(submission.get("overdue").asBoolean()).isFalse();
        assertThat(submission.get("endorsedLate").asBoolean()).isFalse();
        assertThat(submission.get("submitDue").asString()).isEqualTo("2027-08-15");
        assertThat(submission.get("reviewDue").asString()).isEqualTo("2027-08-29");
        assertThat(submission.get("endorseDue").asString()).isEqualTo("2027-10-31");
        assertThat(submission.get("startedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(submission.get("acted")).hasSize(1);
        JsonNode acted = submission.get("acted").get(0);
        assertThat(acted.get("step").asString()).isEqualTo("SUBMIT");
        assertThat(acted.get("userId").asString()).isEqualTo(userId(smo));
        assertThat(acted.get("at").asString()).isEqualTo(clock.instant().toString());
    }

    @Test
    void aSecondStartAnswers409InvalidTransitionAndChangesNothing() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        JsonNode first = started(smo, idOf("SYS-S1"));
        clock.advance(Duration.ofMinutes(5));

        var again = start(smo, idOf("SYS-S1"));

        assertThat(again.status()).isEqualTo(409);
        assertThat(problemType(again)).isEqualTo("https://annualva/problems/invalid-transition");
        JsonNode kept = ApiClient.json(read(smo, first.get("id").asString()));
        assertThat(kept).as("the first VA Submission is untouched").isEqualTo(first);
    }

    @Test
    void startingIsAllowedBeforeTheQuarterBeginsAndTakesThatQuartersWindows() {
        registerSystems();

        JsonNode submission = started(signedInAs(SMO_A), idOf("SYS-S2"));

        assertThat(submission.get("quarter").asInt()).isEqualTo(4);
        assertThat(submission.get("state").asString()).isEqualTo("DRAFT");
        assertThat(submission.get("submitDue").asString()).isEqualTo("2027-11-14");
        assertThat(submission.get("reviewDue").asString()).isEqualTo("2027-11-28");
        assertThat(submission.get("endorseDue").asString()).isEqualTo("2028-02-06");
    }

    @Test
    void startingIsAllowedBeforeTheFirstLiveCycleBeginsAndTakesThatCyclesWindows() {
        today("2027-01-04");
        registerSystems();

        JsonNode submission = started(signedInAs(SMO_A), idOf("SYS-S1"));

        assertThat(submission.get("cycle").asInt()).as("the first live Cycle, upcoming").isEqualTo(2027);
        assertThat(submission.get("submitDue").asString()).isEqualTo("2027-08-15");
    }

    @Test
    void aSystemWhoseQuarterIsHandledOutsideTheAppIsNotStarted() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);

        var response = start(smo, idOf("SYS-S0"));

        assertThat(response.status()).isEqualTo(409);
        assertThat(problemType(response)).isEqualTo("https://annualva/problems/invalid-transition");
        assertThat(ApiClient.json(smo.get("/api/dashboards/smo-inbox")).get("handledOutsideApp")).hasSize(1);
    }

    @Test
    void theCycleCanBeNamedAndACycleTheAppDoesNotRunIsNotFound() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);

        assertThat(start(smo, idOf("SYS-S1"), 2026).status()).as("before the first live Cycle").isEqualTo(404);
        assertThat(start(smo, idOf("SYS-S1"), 2099).status()).as("a Cycle that has not begun").isEqualTo(404);
        var named = start(smo, idOf("SYS-S1"), 2027);
        assertThat(named.status()).as(named.body()).isEqualTo(201);
        assertThat(ApiClient.json(named).get("cycle").asInt()).isEqualTo(2027);
    }

    @Test
    void aRequestWithoutASystemOrWithAMalformedOneIsRefusedWithoutEchoingIt() {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);

        var missing = smo.postJson(SUBMISSIONS, "{}");
        var malformed = smo.postJson(SUBMISSIONS, "{\"systemId\":\"CVE-9999-SENTINEL\"}");
        var wrongCycle =
                smo.postJson(SUBMISSIONS, "{\"systemId\":\"" + idOf("SYS-S1") + "\",\"cycle\":\"CVE-9999-SENTINEL\"}");

        assertThat(missing.status()).isEqualTo(400);
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(wrongCycle.status()).isEqualTo(400);
        assertThat(missing.body() + malformed.body() + wrongCycle.body()).doesNotContain("SENTINEL");
    }

    @Test
    void twoStartsAtTheSameTimeMakeOneVaSubmissionAndRefuseTheOther() throws Exception {
        registerSystems();
        ApiClient smo = signedInAs(SMO_A);
        String system = idOf("SYS-S1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> start = () -> start(smo, system).status();
            Future<Integer> one = pool.submit(start);
            Future<Integer> two = pool.submit(start);

            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aVaPocWhoIsAlsoTheSmoOfTheSystemMayStartItAndIsTheSubmitter() {
        registered(listed("SYS-S3", "Start three", POC_ONE, CM, "Q3"));
        String system = idOf("SYS-S3");
        ApiClient poc = vaPoc();

        JsonNode submission = started(poc, system);

        assertThat(submission.get("acted").get(0).get("userId").asString()).isEqualTo(userId(poc));
        assertThat(submission.get("acted").get(0).get("step").asString()).isEqualTo("SUBMIT");
    }
}
