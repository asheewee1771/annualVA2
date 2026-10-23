package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 23 share (stories 25 and 26): the scan date and target a report names, confirmed or corrected by
 * the SMO/Tech Lead, and the age rules (a file dated after today is rejected by the pipeline; one more than 90 days old at
 * today's business date is named by the readiness check). The default clock is 09:00 on Monday 2 August 2027 in Singapore;
 * the samples were scanned on 20 July 2027.
 */
abstract class ScanDateSeam1 extends ReplaceSeam1 {

    /** A Dependency-Check JSON report with one Finding, made at this instant (ISO text) or with no date when null. */
    protected static byte[] dcReportAt(String reportDate) {
        String projectInfo = reportDate == null ? "{\"name\":\"throwaway-project\"}"
                : "{\"name\":\"throwaway-project\",\"reportDate\":\"" + reportDate + "\"}";
        return jsonReportOf(projectInfo, dependency(PKG_A, "/app/alpha.js", "CVE-2027-0001", "9.8"));
    }

    /** A Burp export with no DOCTYPE, made at this export time, holding these issues as written. */
    protected static byte[] burpExportAt(String exportTime, String issues) {
        return utf8("<?xml version=\"1.0\"?>\n<issues burpVersion=\"2023.1.2\" exportTime=\"" + exportTime + "\">" + issues
                + "</issues>");
    }

    /** One plain Burp issue with every element a Finding needs. */
    protected static final String BURP_ISSUE = "<issue><type>1</type><name>Throwaway issue</name>"
            + "<host>https://throwaway.invalid</host><path>/</path><location>/</location><severity>High</severity>"
            + "<confidence>Certain</confidence></issue>";

    protected static String metadataOf(String submissionId, String fileId) {
        return fileAt(submissionId, fileId) + "/metadata";
    }

    /** The body of a confirmation; a null field is left out. */
    protected static String confirmation(String scanDate, String target) {
        List<String> fields = new ArrayList<>();
        if (scanDate != null) {
            fields.add("\"scanDate\":" + quoted(scanDate));
        }
        if (target != null) {
            fields.add("\"target\":" + quoted(target));
        }
        return "{" + String.join(",", fields) + "}";
    }

    protected static ApiClient.Response confirm(ApiClient client, String submissionId, String fileId, String json) {
        return client.putJson(metadataOf(submissionId, fileId), json);
    }

    /** Confirms or corrects the scan date and target and expects it to be kept (200); the metadata as it is now. */
    protected static JsonNode confirmed(ApiClient client, String submissionId, String fileId, String scanDate,
            String target) {
        var response = confirm(client, submissionId, fileId, confirmation(scanDate, target));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("cache-control")).contains("no-store");
        return ApiClient.json(response);
    }

    protected static String readinessOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/readiness";
    }

    protected static JsonNode readiness(ApiClient client, String submissionId) {
        var response = client.get(readinessOf(submissionId));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** "name (REPORT_TYPE)" for each file of a readiness list, in the order given. */
    protected static List<String> named(JsonNode files) {
        List<String> names = new ArrayList<>();
        files.forEach(file -> names.add(file.get("name").asString() + " (" + file.get("reportType").asString() + ")"));
        return names;
    }

    /** Uploads a Dependency-Check file and runs the job until its verdict is in; its id. */
    protected String parsedDc(Draft draft, String name, byte[] content) {
        JsonNode file = settledDc(draft, name, content);
        assertThat(file.get("status").asString()).as(file.toString()).isEqualTo("PARSED");
        return file.get("id").asString();
    }
}
