package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Samples;
import tools.jackson.databind.JsonNode;

/**
 * What the Finding Status proposal tests share: a VA Submission in Draft whose Dependency-Check sample was read into seven
 * Findings, and the calls a person's browser makes to propose a status for one of them. The default clock is 09:00 on Monday
 * 2 August 2027 (Singapore), so a target date of 2027-12-31 is in the future.
 */
abstract class ProposalSeam1 extends DependencyCheckSeam1 {

    static final String SENTINEL = "SENTINEL-4419";
    /** A date well after the default clock's today. */
    static final String LATER = "2027-12-31";

    /** The Draft of System SYS-F1 with the Dependency-Check sample read into its Findings. */
    protected Draft draftWithFindings() {
        Draft draft = draft();
        settledDc(draft, "dc.xml", Samples.dependencyCheckSampleXml());
        return draft;
    }

    protected static String proposalsOf(String findingId) {
        return "/api/findings/" + findingId + "/proposals";
    }

    /** The id of the Finding with this identifier, as the SMO reads the list. */
    protected static String findingId(Draft draft, String identifier) {
        return byIdentifier(findings(draft.smo(), draft.submissionId()), identifier).get("id").asString();
    }

    /** The Finding as the list shows it. */
    protected static JsonNode finding(ApiClient client, String submissionId, String identifier) {
        return byIdentifier(findings(client, submissionId), identifier);
    }

    protected static ApiClient.Response propose(ApiClient client, String findingId, String json) {
        return client.postJson(proposalsOf(findingId), json);
    }

    /** Proposes and expects it to be kept (201). */
    protected static JsonNode proposed(ApiClient client, String findingId, String json) {
        var response = propose(client, findingId, json);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return ApiClient.json(response);
    }

    protected static String remediated(String evidence) {
        return "{\"status\":\"REMEDIATED\",\"evidence\":" + quoted(evidence) + "}";
    }

    protected static String falsePositive(String justification) {
        return "{\"status\":\"FALSE_POSITIVE\",\"justification\":" + quoted(justification) + "}";
    }

    protected static String riskAccepted(String reason, String approverName, String approverRole) {
        return "{\"status\":\"RISK_ACCEPTED\",\"reason\":" + quoted(reason) + ",\"approverName\":" + quoted(approverName)
                + ",\"approverRole\":" + quoted(approverRole) + "}";
    }

    protected static String riskAcceptedWithMemo(String reason, String approverName, String approverRole, String memoId) {
        return "{\"status\":\"RISK_ACCEPTED\",\"reason\":" + quoted(reason) + ",\"approverName\":" + quoted(approverName)
                + ",\"approverRole\":" + quoted(approverRole) + ",\"approvalMemoFileId\":" + quoted(memoId) + "}";
    }

    protected static String remediationPlanned(String plan, String targetDate) {
        return "{\"status\":\"REMEDIATION_PLANNED\",\"plan\":" + quoted(plan) + ",\"targetDate\":" + quoted(targetDate) + "}";
    }

    /** A JSON string literal; null is the JSON null, so a field can be left out by writing null. */
    protected static String quoted(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder json = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    /** The answer is a 400 {@code invalid-field} problem that names this field and has not echoed the sentinel. */
    protected static void assertInvalidField(ApiClient.Response response, String field) {
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        JsonNode problem = ApiClient.json(response);
        assertThat(problem.get("type").asString()).isEqualTo("https://annualva/problems/invalid-field");
        assertThat(problem.get("field").asString()).isEqualTo(field);
        assertThat(problem.get("detail").asString()).contains("'" + field + "'");
        assertThat(problem.get("traceId").asString()).isNotBlank();
    }

    protected static void assertNotFound(ApiClient.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(ApiClient.json(response).get("type").asString()).isEqualTo("https://annualva/problems/not-found");
    }

    /** The audit entries of this action about this System, newest first, read as the second VA POC. */
    protected JsonNode entries(String action, String systemId) {
        var response = otherVaPoc().get("/api/audit-entries?size=200&action=" + action + "&system=" + systemId);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response).get("items");
    }
}
