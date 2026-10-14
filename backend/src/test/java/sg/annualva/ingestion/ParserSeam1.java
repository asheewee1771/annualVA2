package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What every parser test shares: a report is uploaded and moved through the pipeline, then its Findings and its pre-filled
 * metadata are read back through the HTTP API, exactly as a person's browser would.
 */
abstract class ParserSeam1 extends IngestionSeam1 {

    protected static String findingsOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/findings";
    }

    /** The Findings the VA Submission holds, as this person reads them (one page of up to 500); expects 200. */
    protected static JsonNode findings(ApiClient client, String submissionId) {
        var response = client.get(findingsOf(submissionId) + "?size=500");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    protected static JsonNode metadata(ApiClient client, String submissionId, String fileId) {
        var response = client.get(fileAt(submissionId, fileId) + "/metadata");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** identity key to "SEVERITY/occurrences" for every Finding on the page, in the order the list gave them. */
    protected static Map<String, String> summary(JsonNode page) {
        Map<String, String> summary = new LinkedHashMap<>();
        for (JsonNode item : page.get("items")) {
            summary.put(item.get("identityKey").asString(),
                    item.get("severity").asString() + "/" + item.get("occurrenceCount").asInt());
        }
        return summary;
    }

    protected static JsonNode byIdentifier(JsonNode page, String identifier) {
        for (JsonNode item : page.get("items")) {
            if (item.get("identifier").asString().equals(identifier)) {
                return item;
            }
        }
        throw new AssertionError("no Finding " + identifier);
    }

    protected static List<String> sourceNames(JsonNode finding) {
        List<String> names = new ArrayList<>();
        finding.get("sourceFiles").forEach(source -> names.add(source.get("name").asString()));
        return names;
    }

    protected static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
