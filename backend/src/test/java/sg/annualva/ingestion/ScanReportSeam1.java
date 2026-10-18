package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 22 share (story 23): how a Scan Report is provided (uploaded, marked not applicable, or entered by
 * hand) and the Findings the SMO/Tech Lead types in, through the calls a person's browser makes.
 */
abstract class ScanReportSeam1 extends ProposalSeam1 {

    static final String UPLOAD = "UPLOAD";
    static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    static final String MANUAL = "MANUAL";

    protected static String reportsOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/reports";
    }

    protected static String modeOf(String submissionId, String reportType) {
        return reportsOf(submissionId) + "/" + reportType + "/mode";
    }

    protected static String manualOf(String submissionId) {
        return findingsOf(submissionId) + "/manual";
    }

    /** The body of a mode change; a null justification is left out. */
    protected static String mode(String mode, String justification) {
        return "{\"mode\":" + quoted(mode) + (justification == null ? "" : ",\"justification\":" + quoted(justification))
                + "}";
    }

    protected static ApiClient.Response setMode(ApiClient client, String submissionId, String reportType, String json) {
        return client.putJson(modeOf(submissionId, reportType), json);
    }

    /** Sets the mode and expects it to be kept (200); the Scan Report as it is now. */
    protected static JsonNode modeSet(ApiClient client, String submissionId, String reportType, String json) {
        var response = setMode(client, submissionId, reportType, json);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** The three Scan Reports as this person reads them; expects 200. */
    protected static JsonNode reports(ApiClient client, String submissionId) {
        var response = client.get(reportsOf(submissionId));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("cache-control")).contains("no-store");
        return ApiClient.json(response);
    }

    /** One Scan Report of the VA Submission as the list reads it. */
    protected static JsonNode report(ApiClient client, String submissionId, String reportType) {
        for (JsonNode report : reports(client, submissionId)) {
            if (report.get("reportType").asString().equals(reportType)) {
                return report;
            }
        }
        throw new AssertionError("no Scan Report " + reportType);
    }

    /** The body of a Finding entered by hand; a null field is left out. */
    protected static String manualFinding(String reportType, String title, String identifier, String packageOrRule,
            String severity) {
        StringBuilder json = new StringBuilder("{");
        appendField(json, "reportType", reportType);
        appendField(json, "title", title);
        appendField(json, "identifier", identifier);
        appendField(json, "packageOrRule", packageOrRule);
        appendField(json, "severity", severity);
        return json.append('}').toString();
    }

    private static void appendField(StringBuilder json, String name, String value) {
        if (value == null) {
            return;
        }
        if (json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(name).append("\":").append(quoted(value));
    }

    protected static ApiClient.Response enter(ApiClient client, String submissionId, String json) {
        return client.postJson(manualOf(submissionId), json);
    }

    /** Enters a Finding by hand and expects it to be kept (201); the Finding as the list shows it. */
    protected static JsonNode entered(ApiClient client, String submissionId, String json) {
        var response = enter(client, submissionId, json);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response);
    }

    /** The answer is a 409 problem of this type. */
    protected static void assertConflict(ApiClient.Response response, String type) {
        assertThat(response.status()).as(response.body()).isEqualTo(409);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/" + type);
    }

    /** The Draft of SYS-F1 with its Dependency-Check Scan Report marked for Findings entered by hand. */
    protected Draft manualDraft() {
        Draft draft = draft();
        modeSet(draft.smo(), draft.submissionId(), DEPENDENCY_CHECK, mode(MANUAL, "The tool exports PDF only."));
        return draft;
    }
}
