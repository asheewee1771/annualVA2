package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import sg.annualva.harness.ApiClient;
import tools.jackson.databind.JsonNode;

/**
 * What the tests of ticket 60 share (stories 23 and 39): the calls the prepare screen makes to delete a report file
 * uploaded by mistake ({@code DELETE /api/submissions/{id}/files/{fileId}}) or a Finding entered by hand by mistake
 * ({@code DELETE /api/findings/{findingId}}).
 */
abstract class DeleteSeam1 extends ReturnSeam1 {

    protected static String findingAt(String findingId) {
        return "/api/findings/" + findingId;
    }

    static ApiClient.Response deleteFile(ApiClient client, String submissionId, String fileId) {
        return client.delete(fileAt(submissionId, fileId));
    }

    static ApiClient.Response deleteFinding(ApiClient client, String findingId) {
        return client.delete(findingAt(findingId));
    }

    /** Deletes the file and expects it gone (204, no body). */
    protected static void fileDeleted(ApiClient client, String submissionId, String fileId) {
        var response = deleteFile(client, submissionId, fileId);
        assertThat(response.status()).as(response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    /** Deletes the Finding and expects it gone (204, no body). */
    protected static void findingDeleted(ApiClient client, String findingId) {
        var response = deleteFinding(client, findingId);
        assertThat(response.status()).as(response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    /** The ids of the VA Submission's files, as this person lists them. */
    protected static List<String> fileIds(ApiClient client, String submissionId) {
        var response = client.get(filesOf(submissionId));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        ApiClient.json(response).forEach(file -> ids.add(file.get("id").asString()));
        return ids;
    }

    /** The identifiers of the VA Submission's Findings, as the SMO/Tech Lead lists them. */
    protected static List<String> identifiers(Draft draft) {
        List<String> identifiers = new ArrayList<>();
        findings(draft.smo(), draft.submissionId()).get("items")
                .forEach(item -> identifiers.add(item.get("identifier").asString()));
        return identifiers;
    }

    /** The only entry of this action and outcome about the System; fails when there is not exactly one. */
    protected JsonNode onlyEntry(String action, String outcome, String systemId) {
        List<JsonNode> matching = new ArrayList<>();
        entries(action, systemId).forEach(entry -> {
            if (entry.get("outcome").asString().equals(outcome)) {
                matching.add(entry);
            }
        });
        assertThat(matching).as(action + " " + outcome).hasSize(1);
        return matching.getFirst();
    }
}
