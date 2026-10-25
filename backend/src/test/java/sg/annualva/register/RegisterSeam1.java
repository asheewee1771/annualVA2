package sg.annualva.register;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.AuditEntries;
import sg.annualva.harness.RegisterXlsx;
import sg.annualva.harness.Seam1Test;
import sg.annualva.harness.SignIn;
import sg.annualva.harness.TestIdentity;
import tools.jackson.databind.JsonNode;

/**
 * What the Register upload tests share. The suite shares one database and one application, so each test starts and
 * ends with an empty System Register and puts the clock back; people are signed in through the HTTP API like anyone.
 */
public abstract class RegisterSeam1 extends Seam1Test {

    static final String UPLOAD = "/api/system-register/uploads";
    static final String PREVIEWS = "/api/system-register/previews/";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    protected static final String POC_ONE = "poc.one@ci.annualva.test";
    protected static final String POC_TWO = "poc.two@ci.annualva.test";

    private Instant before;

    @BeforeEach
    protected void startWithAnEmptyRegister() {
        before = clock.instant();
        arrange.clearRegister();
    }

    @AfterEach
    protected void leaveAnEmptyRegister() {
        clock.set(before);
        arrange.clearRegister();
    }

    /** A filter that reads the audit log up to today's business date ({@link AuditEntries#untilToday}). */
    protected String untilToday() {
        return AuditEntries.untilToday(clock);
    }

    /** A sign-in whose auth_time is the application clock's now, so the person is within the step-up window. */
    protected TestIdentity fresh(TestIdentity identity) {
        return identity.withAuthTime(clock.instant());
    }

    /** The first VA POC, signed in, with a CSRF token. */
    protected ApiClient vaPoc() {
        return freshVaPoc();
    }

    /** The second VA POC, signed in, with a CSRF token. */
    protected ApiClient otherVaPoc() {
        ApiClient browser = anotherBrowser();
        SignIn.signedIn(browser, issuer, fresh(TestIdentity.of("af-5002", POC_TWO, "POC Two")));
        return browser.withCsrf();
    }

    /** A person who has signed in as this email, with whatever roles they hold, and a CSRF token. */
    protected ApiClient signedInAs(String email) {
        ApiClient browser = anotherBrowser();
        SignIn.signedIn(browser, issuer, fresh(TestIdentity.of(ssoIdOf(email), email, "Person")));
        return browser.withCsrf();
    }

    /** The {@code afcas.uuid} the identity provider asserts for the person {@link #signedInAs} signs in. */
    protected static String ssoIdOf(String email) {
        return "af-r-" + Math.abs(email.hashCode());
    }

    /** One System as a VA POC's file lists it. */
    protected record Listed(String systemId, String name, String smo, String manager, String quarter) {}

    protected static Listed listed(String systemId, String name, String smo, String manager, String quarter) {
        return new Listed(systemId, name, smo, manager, quarter);
    }

    protected static RegisterXlsx file(List<Listed> systems) {
        RegisterXlsx file = RegisterXlsx.register();
        systems.forEach(s -> file.row(s.systemId(), s.name(), s.smo(), s.manager(), s.quarter()));
        return file;
    }

    /** Applies a preview as this VA POC, who has just signed in, and expects it to be applied. */
    protected static JsonNode apply(ApiClient poc, JsonNode preview) {
        var response = applyResponse(poc, preview);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    protected static ApiClient.Response applyResponse(ApiClient poc, JsonNode preview) {
        return poc.post(PREVIEWS + preview.get("id").asString() + "/apply");
    }

    /** Applies a preview confirming these withdrawals one by one, as the Register screen sends it (story 6). */
    protected static ApiClient.Response applyConfirming(ApiClient poc, JsonNode preview, List<String> withdraw) {
        String id = preview.get("id").asString();
        String ids = withdraw.stream().map(item -> "\"" + item + "\"").collect(java.util.stream.Collectors.joining(","));
        return poc.postJson(PREVIEWS + id + "/apply", "{\"previewId\":\"" + id + "\",\"withdraw\":[" + ids + "]}");
    }

    /**
     * Puts exactly these Systems in the Register, the way a VA POC does: upload the file and apply the preview. The
     * Register is one standing list, so whatever it held before is retired (and its grants revoked).
     */
    protected void registered(Listed... systems) {
        registered(List.of(systems), List.of());
    }

    /**
     * Puts exactly these Systems in the Register although the change moves, transfers or retires Systems whose VA
     * Submissions are in flight, which Release 1 blocks or withdraws (ADR 0049): every in-flight VA Submission is stood
     * aside in a final state while the apply runs, as Release 2's mid-cycle changes will allow, and is then put back in
     * the state it was in. For a test of what follows such a change: segregation of duties, the access guard, the sweep.
     */
    protected void registeredMidCycle(Listed... systems) {
        java.util.Map<String, String> inFlight = arrange.standAsideInFlight();
        registered(systems);
        inFlight.forEach(arrange::submissionState);
    }

    /**
     * The same, and then these Systems retired by a second upload that leaves them out. At least one System must stay
     * active, because a file with no rows is refused.
     */
    protected void registered(List<Listed> active, List<Listed> retired) {
        ApiClient poc = vaPoc();
        List<Listed> first = new ArrayList<>(active);
        first.addAll(retired);
        apply(poc, preview(poc, file(first)));
        if (!retired.isEmpty()) {
            apply(poc, preview(poc, file(active)));
        }
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
