package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sg.annualva.harness.ApiClient;
import sg.annualva.submission.SubmissionSeam1;
import tools.jackson.databind.JsonNode;

/**
 * What the Scan Report upload tests share. The Register is filled the way a VA POC fills it, an SMO/Tech Lead starts the
 * VA Submission of System SYS-F1 (whose Cyber Manager is {@link #CM}), and files are uploaded through the HTTP API. The
 * scan job is moved forward by hand, one pass at a time, because scheduling is off in tests; the scanner and the file
 * store are the fakes, emptied before every test. The default clock is 09:00 on Monday 2 August 2027.
 */
abstract class IngestionSeam1 extends SubmissionSeam1 {

    static final String DEPENDENCY_CHECK = "DEPENDENCY_CHECK";
    static final String SECURE_CONFIGURATION = "SECURE_CONFIGURATION";
    static final String BURP_SUITE = "BURP_SUITE";
    static final String MACHINE_READABLE = "MACHINE_READABLE";
    static final String EVIDENCE = "EVIDENCE";

    /** A started VA Submission and the SMO/Tech Lead who started it. */
    protected record Draft(ApiClient smo, String systemId, String submissionId) {}

    /** SYS-F1 (SMO_A, CM) and SYS-F2 (SMO_B, CM_OTHER) are registered; SMO_A has started the VA Submission of SYS-F1. */
    protected Draft draft() {
        registered(
                List.of(listed("SYS-F1", "File one", SMO_A, CM, "Q3"), listed("SYS-F2", "File two", SMO_B, CM_OTHER, "Q3")),
                List.of());
        ApiClient smo = signedInAs(SMO_A);
        String system = idOf("SYS-F1");
        Draft draft = new Draft(smo, system, started(smo, system).get("id").asString());
        // The Register upload went through the scanner too; a test counts only what it submits itself.
        scanner.reset();
        return draft;
    }

    protected static String filesOf(String submissionId) {
        return SUBMISSIONS + "/" + submissionId + "/files";
    }

    protected static String fileAt(String submissionId, String fileId) {
        return filesOf(submissionId) + "/" + fileId;
    }

    /** Uploads a file the way the SPA does and returns whatever the server answered. */
    protected static ApiClient.Response upload(ApiClient client, String submissionId, String reportType, String kind,
            String fileName, byte[] content) {
        return client.postMultipart(filesOf(submissionId), Map.of("reportType", reportType, "kind", kind), "file",
                fileName, "application/octet-stream", content);
    }

    /** Uploads a machine-readable file of the Scan Report and expects it to be accepted for scanning (202). */
    protected static JsonNode uploaded(ApiClient client, String submissionId, String reportType, String fileName,
            byte[] content) {
        var response = upload(client, submissionId, reportType, MACHINE_READABLE, fileName, content);
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        return ApiClient.json(response);
    }

    /** Uploads a supporting evidence file of the Scan Report and expects it to be accepted for scanning (202). */
    protected static JsonNode uploadedEvidence(ApiClient client, String submissionId, String reportType, String fileName,
            byte[] content) {
        var response = upload(client, submissionId, reportType, EVIDENCE, fileName, content);
        assertThat(response.status()).as(response.body()).isEqualTo(202);
        return ApiClient.json(response);
    }

    /** The status of one file, as this person reads it; expects 200. */
    protected static JsonNode status(ApiClient client, String submissionId, String fileId) {
        var response = client.get(fileAt(submissionId, fileId));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return ApiClient.json(response);
    }

    /** One pass of the scan job. */
    protected void pass() {
        arrange.scan();
    }

    /** Uploads a file and runs the job until its verdict is in: the first pass submits it, the second reads the answer. */
    protected JsonNode settled(Draft draft, String reportType, String fileName, byte[] content) {
        String id = uploaded(draft.smo(), draft.submissionId(), reportType, fileName, content).get("id").asString();
        pass();
        pass();
        return status(draft.smo(), draft.submissionId(), id);
    }

    /** Uploads a supporting evidence file and runs the job until its verdict is in. */
    protected JsonNode settledEvidence(Draft draft, String reportType, String fileName, byte[] content) {
        String id = uploadedEvidence(draft.smo(), draft.submissionId(), reportType, fileName, content).get("id").asString();
        pass();
        pass();
        return status(draft.smo(), draft.submissionId(), id);
    }

    /** The statuses a file went through, in order. */
    protected static List<String> stages(JsonNode file) {
        List<String> stages = new ArrayList<>();
        file.get("stages").forEach(stage -> stages.add(stage.get("status").asString()));
        return stages;
    }
}
