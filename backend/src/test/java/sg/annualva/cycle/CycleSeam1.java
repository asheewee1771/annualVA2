package sg.annualva.cycle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * What the calendar tests share. A session idles out by the application clock, so each reading moves the clock first
 * and signs in afterwards; the clock is put back when the test ends because the application is shared by the suite.
 */
abstract class CycleSeam1 extends Seam1Test {

    static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private Instant before;

    @BeforeEach
    void rememberTheClock() {
        before = clock.instant();
    }

    @AfterEach
    void putTheClockBack() {
        clock.set(before);
    }

    /** A VA POC signed in at the given instant, in a browser of its own. */
    protected ApiClient signedInAt(Instant instant) {
        clock.set(instant);
        return SignIn.signedIn(anotherBrowser(), issuer, TestIdentity.of("af-5001", "poc.one@ci.annualva.test", "POC One"));
    }

    /** A VA POC signed in at 09:00 on the Singapore business day. */
    protected ApiClient signedInOn(String businessDay) {
        return signedInAt(LocalDate.parse(businessDay).atTime(LocalTime.of(9, 0)).atZone(SINGAPORE).toInstant());
    }

    protected static JsonNode cycle(ApiClient client, int year) {
        var response = client.get("/api/cycles/" + year);
        org.assertj.core.api.Assertions.assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    protected static JsonNode cycles(ApiClient client) {
        var response = client.get("/api/cycles");
        org.assertj.core.api.Assertions.assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }
}
