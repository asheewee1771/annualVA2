package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RegisterXlsx;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * What the Register upload tests share. The suite shares one database and one application, so each test starts and
 * ends with an empty System Register and puts the clock back; people are signed in through the HTTP API like anyone.
 */
abstract class RegisterSeam1 extends Seam1Test {

    static final String UPLOAD = "/api/system-register/uploads";
    static final String PREVIEWS = "/api/system-register/previews/";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String POC_ONE = "poc.one@ci.annualva.test";
    static final String POC_TWO = "poc.two@ci.annualva.test";

    private Instant before;

    @BeforeEach
    void startWithAnEmptyRegister() {
        before = clock.instant();
        arrange.clearRegister();
    }

    @AfterEach
    void leaveAnEmptyRegister() {
        clock.set(before);
        arrange.clearRegister();
    }

    /** The first VA POC, signed in, with a CSRF token. */
    protected ApiClient vaPoc() {
        ApiClient browser = anotherBrowser();
        SignIn.signedIn(browser, issuer, TestIdentity.of("af-5001", POC_ONE, "POC One"));
        return browser.withCsrf();
    }

    /** The second VA POC, signed in, with a CSRF token. */
    protected ApiClient otherVaPoc() {
        ApiClient browser = anotherBrowser();
        SignIn.signedIn(browser, issuer, TestIdentity.of("af-5002", POC_TWO, "POC Two"));
        return browser.withCsrf();
    }

    /** A person who has signed in as this email, with whatever roles they hold, and a CSRF token. */
    protected ApiClient signedInAs(String email) {
        ApiClient browser = anotherBrowser();
        SignIn.signedIn(browser, issuer, TestIdentity.of("af-r-" + Math.abs(email.hashCode()), email, "Person"));
        return browser.withCsrf();
    }

    protected static ApiClient.Response upload(ApiClient client, byte[] file) {
        return client.postFile(UPLOAD, "file", "register.xlsx", XLSX, file);
    }

    /** Uploads the file as asked and expects a preview. */
    protected static JsonNode preview(ApiClient client, RegisterXlsx file) {
        var response = upload(client, file.build());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response);
    }

    /** The row for one System ID, as typed. */
    protected static JsonNode rowOf(JsonNode preview, String systemId) {
        for (JsonNode row : preview.get("rows")) {
            if (row.hasNonNull("systemId") && row.get("systemId").asString().equals(systemId)) {
                return row;
            }
        }
        throw new AssertionError("no row for " + systemId + " in " + preview);
    }

    /** A row is BLOCKED when that is its one outcome. */
    protected static boolean blocked(JsonNode row) {
        return "BLOCKED".equals(row.get("outcome").asString());
    }

    protected static String problemType(ApiClient.Response response) {
        return ApiClient.json(response).get("type").asString();
    }
}
