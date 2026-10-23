package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.RecordedSubmissionEvents;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 25 share (stories 40 and 41): a VA Submission made ready to submit through the calls a person's
 * browser makes, the Declaration as the server gives it, the submit call and the reasons a refusal lists. The default
 * clock is 09:00 on Monday 2 August 2027 in Singapore; the reports here were scanned on 20 July 2027.
 */
abstract class SubmitSeam1 extends ScanReportSeam1 {

    /** When the reports here were scanned, in Singapore: 13 days before the default clock. */
    static final String SCANNED = "2027-07-20T10:00:00+08:00";
    static final String DECLARATION_TEXT = "/api/declaration-text";

    @Autowired
    protected RecordedSubmissionEvents events;

    @BeforeEach
    void noEventsYet() {
        events.reset();
    }

    protected static String submitOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/submit";
    }

    /** The current Declaration version, as the SMO/Tech Lead reads it. */
    protected static int currentVersion(ApiClient client) {
        var response = client.get(DECLARATION_TEXT);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("version").asInt();
    }

    /** The body of a submit: whether the Declaration was ticked, and the version shown; a null field is left out. */
    protected static String declaration(Boolean accepted, Integer version) {
        List<String> fields = new ArrayList<>();
        if (accepted != null) {
            fields.add("\"declarationAccepted\":" + accepted);
        }
        if (version != null) {
            fields.add("\"declarationVersion\":" + version);
        }
        return "{" + String.join(",", fields) + "}";
    }

    protected static ApiClient.Response submit(ApiClient client, String submissionId, String json) {
        return client.postJson(submitOf(submissionId), json);
    }

    /** Ticks the current Declaration and submits. */
    protected static ApiClient.Response submitAccepting(ApiClient client, String submissionId) {
        return submit(client, submissionId, declaration(true, currentVersion(client)));
    }

    /** Ticks the current Declaration, submits and expects it to go to review (200); the VA Submission as answered. */
    protected static JsonNode submitted(ApiClient client, String submissionId) {
        var response = submitAccepting(client, submissionId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /**
     * The Draft of SYS-F1 with nothing standing in the way of submitting: a Dependency-Check report read into one Finding,
     * Secure Configuration entered by hand with one Finding, Burp Suite not applicable, and every Finding proposed.
     */
    protected Draft readyDraft() {
        Draft draft = draft();
        String sub = draft.submissionId();
        parsedDc(draft, "dc.json", dcReportAt(SCANNED));
        modeSet(draft.smo(), sub, SECURE_CONFIGURATION, mode(MANUAL, "The scanner exports PDF only."));
        entered(draft.smo(), sub, manualFinding(SECURE_CONFIGURATION, "Password policy too weak", "SC-PWD-1",
                "password-min-length", "HIGH"));
        modeSet(draft.smo(), sub, BURP_SUITE, mode(NOT_APPLICABLE, "The System has no web interface."));
        proposeEvery(draft);
        return draft;
    }

    /** Proposes False Positive for every Finding of the VA Submission that has no proposal yet. */
    protected static void proposeEvery(Draft draft) {
        for (JsonNode finding : findings(draft.smo(), draft.submissionId()).get("items")) {
            if (finding.get("currentProposal").isNull()) {
                proposed(draft.smo(), finding.get("id").asString(), falsePositive("Not reachable in this System."));
            }
        }
    }

    /** The reasons of a refused submit: it is a 409 {@code not-ready} problem, and these are its reasons in order. */
    protected static JsonNode refusalReasons(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(409);
        JsonNode problem = ApiClient.json(response);
        assertThat(problem.get("type").asString()).isEqualTo("https://annualva/problems/not-ready");
        assertThat(problem.get("traceId").asString()).isNotBlank();
        return problem.get("reasons");
    }

    /** The reason codes of a list of reasons, in order. */
    protected static List<String> codes(JsonNode reasons) {
        List<String> codes = new ArrayList<>();
        reasons.forEach(reason -> codes.add(reason.get("reason").asString()));
        return codes;
    }

    /** The one reason with this code. */
    protected static JsonNode reason(JsonNode reasons, String code) {
        for (JsonNode reason : reasons) {
            if (reason.get("reason").asString().equals(code)) {
                return reason;
            }
        }
        throw new AssertionError("no reason " + code + " in " + reasons);
    }

    /** What stands in the way of submitting, as the prepare screen reads it. */
    protected static JsonNode blockers(ApiClient client, String submissionId) {
        return readiness(client, submissionId).get("blockers");
    }

    /** The VA Submission as its SMO/Tech Lead reads it. */
    protected static JsonNode submission(ApiClient client, String submissionId) {
        var response = read(client, submissionId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }
}
